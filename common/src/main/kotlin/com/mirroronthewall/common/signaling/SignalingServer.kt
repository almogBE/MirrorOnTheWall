package com.mirroronthewall.common.signaling

import com.mirroronthewall.common.MirrorConstants
import com.mirroronthewall.common.pairing.PairingAuthority
import com.mirroronthewall.common.protocol.Bye
import com.mirroronthewall.common.protocol.DisplayInfo
import com.mirroronthewall.common.protocol.Hello
import com.mirroronthewall.common.protocol.Ping
import com.mirroronthewall.common.protocol.Pong
import com.mirroronthewall.common.protocol.Reject
import com.mirroronthewall.common.protocol.RejectReason
import com.mirroronthewall.common.protocol.SignalingCodec
import com.mirroronthewall.common.protocol.SignalingMessage
import com.mirroronthewall.common.protocol.Welcome
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import org.java_websocket.WebSocket
import org.java_websocket.exceptions.WebsocketNotConnectedException
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Receiver-side signaling server (docs/ARCHITECTURE.md §5.1–5.3).
 *
 * - Listens on [preferredPort]. If that port is taken, it falls back to any free
 *   port. [state] reports the actual port.
 * - Allows at most one active session. A [Hello] from the *same* sender replaces
 *   the old session (fast reconnect). A different sender gets [RejectReason.BUSY].
 * - Handles [Hello], [Ping] and [Bye] itself. Every other message from the active
 *   sender is delivered through [events].
 *
 * Thread-safe. [start] and [stop] can be called repeatedly. [stop] blocks for up to
 * about 1 s while sockets close, so call it off the main thread.
 *
 * [events] has a single consumer and an unbounded buffer, so no message is dropped
 * between a session starting and the consumer reacting.
 */
class SignalingServer(
    private val preferredPort: Int,
    private val pairing: PairingAuthority,
    private val identity: () -> ReceiverIdentity,
    private val helloTimeoutMs: Long = 5_000,
    private val connectionLostTimeoutSec: Int = 4,
) {
    data class ReceiverIdentity(
        val receiverId: String,
        val receiverName: String,
        val display: DisplayInfo,
    )

    data class Peer(
        val sessionId: Long,
        val senderId: String,
        val senderName: String,
        val remoteAddress: String,
        val newlyPaired: Boolean,
    )

    sealed interface State {
        data object Stopped : State
        data object Starting : State
        data class Listening(val port: Int) : State
        data class Failed(val error: Throwable) : State
    }

    sealed interface Event {
        data class SessionStarted(val peer: Peer) : Event
        data class SessionEnded(val peer: Peer, val reason: String) : Event
        data class MessageReceived(val peer: Peer, val message: SignalingMessage) : Event
        data class SenderRejected(val senderName: String?, val remoteAddress: String, val reason: RejectReason) : Event
    }

    private val lock = Any()
    private val _state = MutableStateFlow<State>(State.Stopped)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _activePeer = MutableStateFlow<Peer?>(null)
    val activePeer: StateFlow<Peer?> = _activePeer.asStateFlow()

    private val eventChannel = Channel<Event>(Channel.UNLIMITED)
    val events: Flow<Event> = eventChannel.receiveAsFlow()

    private val timer = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "SignalingServer-timer").apply { isDaemon = true }
    }

    // Guarded by lock.
    private var server: Impl? = null
    private var activeConn: WebSocket? = null
    private var nextSessionId = 1L

    private class ConnInfo(val sessionId: Long) {
        @Volatile var peer: Peer? = null
        @Volatile var helloTimeout: ScheduledFuture<*>? = null

        /** Why the session ended, set by whoever first knows (bye, replace, stop). */
        @Volatile var endReason: String? = null
    }

    fun start() {
        synchronized(lock) {
            if (server != null) return
            _state.value = State.Starting
            launchLocked(preferredPort)
        }
    }

    /** Ends the session (with a [Bye]) and closes the server. Blocks up to about 1 s. */
    fun stop() {
        val s: Impl?
        val conn: WebSocket?
        synchronized(lock) {
            s = server
            server = null
            conn = activeConn
            conn?.info()?.endReason = "receiver stopped"
            _state.value = State.Stopped
        }
        conn?.let { sendQuietly(it, Bye("receiver stopped")) }
        try {
            s?.stop(1_000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** [stop], then release the timer thread and end [events]. The instance can't be reused afterwards. */
    fun close() {
        stop()
        timer.shutdownNow()
        eventChannel.close()
    }

    /** Sends to the active sender. Returns false if there is no open session. */
    fun send(message: SignalingMessage): Boolean {
        val conn = synchronized(lock) { activeConn } ?: return false
        return sendQuietly(conn, message)
    }

    /** Ends the active session from the TV side, with a [Bye]. */
    fun endSession(reason: String) {
        val conn = synchronized(lock) { activeConn } ?: return
        conn.info()?.endReason = "ended by receiver: $reason"
        sendQuietly(conn, Bye(reason))
        conn.close(CLOSE_NORMAL, reason)
    }

    // ---------------------------------------------------------------- internals
    //
    // Locking rule: never call into a WebSocket (send/close) while holding `lock`.
    // Java-WebSocket calls onClose while holding the connection's monitor
    // (WebSocketImpl.closeConnection is synchronized), and onClosed takes `lock`.
    // Doing socket I/O under `lock` could therefore deadlock.

    private fun launchLocked(port: Int) {
        val s = Impl(port).apply {
            isReuseAddr = true
            isTcpNoDelay = true
            connectionLostTimeout = connectionLostTimeoutSec
            setDaemon(true)
        }
        server = s
        s.start()
    }

    private fun WebSocket.info(): ConnInfo? = getAttachment<ConnInfo>()

    private fun sendQuietly(conn: WebSocket, message: SignalingMessage): Boolean =
        try {
            if (!conn.isOpen) {
                false
            } else {
                conn.send(SignalingCodec.encode(message))
                true
            }
        } catch (e: WebsocketNotConnectedException) {
            false
        }

    private fun remoteAddress(conn: WebSocket): String =
        conn.remoteSocketAddress?.let { "${it.address?.hostAddress}:${it.port}" } ?: "?"

    private fun onServerStarted(s: Impl) {
        synchronized(lock) {
            if (server === s) _state.value = State.Listening(s.port)
        }
    }

    private fun onServerError(s: Impl, error: Exception) {
        synchronized(lock) {
            if (server !== s) return
            if (!s.started && s.address.port != 0) {
                // Typically BindException: the preferred port is taken. Any free port
                // works, because the actual port is advertised over NSD and shown on screen.
                launchLocked(0)
                return
            }
            server = null
            _state.value = State.Failed(error)
        }
    }

    private fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val path = handshake.resourceDescriptor.substringBefore('?')
        if (path != MirrorConstants.SIGNALING_PATH) {
            conn.close(CLOSE_PROTOCOL_ERROR, "unknown path")
            return
        }
        val info = synchronized(lock) { ConnInfo(nextSessionId++) }
        conn.setAttachment(info)
        info.helloTimeout = timer.schedule(
            { if (info.peer == null) conn.close(CLOSE_HELLO_TIMEOUT, "hello timeout") },
            helloTimeoutMs,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun onText(conn: WebSocket, text: String) {
        val info = conn.info() ?: return
        val message = SignalingCodec.decode(text)
        val peer = info.peer
        if (peer == null) {
            onHello(conn, info, message)
            return
        }
        when (message) {
            // Unknown type from a newer peer: ignore (forward compatible).
            null -> Unit
            is Ping -> sendQuietly(conn, Pong(message.seq, message.senderTimeNanos))
            is Bye -> {
                info.endReason = "phone said bye: ${message.reason}"
                conn.close(CLOSE_NORMAL, "bye")
            }
            is Hello -> Unit // Duplicate hello on an open session: ignore.
            else -> synchronized(lock) {
                if (activeConn === conn) eventChannel.trySend(Event.MessageReceived(peer, message))
            }
        }
    }

    private fun onHello(conn: WebSocket, info: ConnInfo, message: SignalingMessage?) {
        info.helloTimeout?.cancel(false)
        val hello = message as? Hello
        if (hello == null) {
            reject(conn, null, RejectReason.PROTOCOL_ERROR)
            return
        }
        if (hello.protocolVersion != MirrorConstants.PROTOCOL_VERSION) {
            reject(conn, hello.senderName, RejectReason.VERSION_MISMATCH)
            return
        }
        // Decide under the lock, do the socket I/O after releasing it (see locking rule).
        var rejectReason: RejectReason? = null
        var accepted: PairingAuthority.Decision.Accepted? = null
        var replaced: WebSocket? = null
        var peer: Peer? = null
        synchronized(lock) {
            val current = activeConn
            val currentPeer = current?.info()?.peer
            if (current != null && currentPeer != null && currentPeer.senderId != hello.senderId) {
                rejectReason = RejectReason.BUSY
            } else {
                when (val decision = pairing.authorize(hello)) {
                    PairingAuthority.Decision.CodeRequired -> rejectReason = RejectReason.PAIRING_REQUIRED
                    PairingAuthority.Decision.WrongCode -> rejectReason = RejectReason.BAD_CODE
                    is PairingAuthority.Decision.Accepted -> {
                        accepted = decision
                        val p = Peer(
                            sessionId = info.sessionId,
                            senderId = hello.senderId,
                            senderName = hello.senderName,
                            remoteAddress = remoteAddress(conn),
                            newlyPaired = decision.newlyPaired,
                        )
                        peer = p
                        info.peer = p
                        activeConn = conn
                        if (current != null && currentPeer != null && current !== conn) {
                            // Same phone reconnected: the old socket is stale. Its onClose
                            // won't match activeConn any more, so report its end here.
                            current.info()?.endReason = "replaced by a new connection from the same phone"
                            replaced = current
                            eventChannel.trySend(Event.SessionEnded(currentPeer, "replaced by a new connection"))
                        }
                        _activePeer.value = p
                    }
                }
            }
        }

        rejectReason?.let {
            reject(conn, hello.senderName, it)
            return
        }
        replaced?.close(CLOSE_REPLACED, "replaced")
        val decision = accepted ?: return
        val newPeer = peer ?: return
        val id = identity()
        sendQuietly(
            conn,
            Welcome(
                protocolVersion = MirrorConstants.PROTOCOL_VERSION,
                receiverId = id.receiverId,
                receiverName = id.receiverName,
                trustToken = decision.trustToken,
                display = id.display,
            ),
        )
        // Emitted after the Welcome is on the wire, so anything the consumer sends
        // in reaction is guaranteed to arrive after it.
        synchronized(lock) {
            if (activeConn === conn) eventChannel.trySend(Event.SessionStarted(newPeer))
        }
    }

    private fun reject(conn: WebSocket, senderName: String?, reason: RejectReason) {
        sendQuietly(conn, Reject(reason, MirrorConstants.PROTOCOL_VERSION))
        conn.close(CLOSE_REJECTED, reason.name)
        eventChannel.trySend(Event.SenderRejected(senderName, remoteAddress(conn), reason))
    }

    private fun onClosed(conn: WebSocket, code: Int, reason: String?) {
        val info = conn.info() ?: return
        info.helloTimeout?.cancel(false)
        synchronized(lock) {
            if (activeConn !== conn) return
            activeConn = null
            _activePeer.value = null
            val peer = info.peer ?: return
            val why = info.endReason ?: "connection lost (code $code${if (reason.isNullOrEmpty()) "" else ", $reason"})"
            eventChannel.trySend(Event.SessionEnded(peer, why))
        }
    }

    private inner class Impl(port: Int) : WebSocketServer(InetSocketAddress(port)) {
        @Volatile var started = false

        override fun onStart() {
            started = true
            onServerStarted(this)
        }

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) =
            this@SignalingServer.onOpen(conn, handshake)

        override fun onMessage(conn: WebSocket, message: String) = onText(conn, message)

        override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) =
            onClosed(conn, code, reason)

        override fun onError(conn: WebSocket?, ex: Exception) {
            // Per-connection errors are always followed by onClose. Only server-level
            // errors (conn == null), such as a failed bind, need handling here.
            if (conn == null) onServerError(this, ex)
        }
    }

    companion object {
        const val CLOSE_NORMAL = 1000
        const val CLOSE_PROTOCOL_ERROR = 1002
        const val CLOSE_REJECTED = 4001
        const val CLOSE_HELLO_TIMEOUT = 4002
        const val CLOSE_REPLACED = 4003
    }
}
