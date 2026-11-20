package com.mirroronthewall.common.signaling

import com.mirroronthewall.common.protocol.Bye
import com.mirroronthewall.common.protocol.Hello
import com.mirroronthewall.common.protocol.Reject
import com.mirroronthewall.common.protocol.RejectReason
import com.mirroronthewall.common.protocol.SignalingCodec
import com.mirroronthewall.common.protocol.SignalingMessage
import com.mirroronthewall.common.protocol.Welcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.exceptions.WebsocketNotConnectedException
import org.java_websocket.handshake.ServerHandshake
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sender-side signaling connection. **One instance per connection attempt.** To
 * reconnect, create a new one.
 *
 * [connect] opens the socket, sends the [Hello], and waits for the [Welcome] or
 * [Reject]. After a successful connect, [events] delivers every later message from
 * the receiver, then a final [Event.Closed]. It has a single consumer and an
 * unbounded buffer, so nothing is lost between [connect] returning and collection
 * starting.
 */
class SignalingClient(
    private val connectionLostTimeoutSec: Int = 4,
) {
    sealed interface ConnectResult {
        data class Connected(val welcome: Welcome) : ConnectResult
        data class Rejected(val reason: RejectReason, val receiverProtocolVersion: Int) : ConnectResult
        data class Failed(val message: String, val cause: Throwable? = null) : ConnectResult
    }

    sealed interface Event {
        data class MessageReceived(val message: SignalingMessage) : Event

        /** The session ended. [byeReason] is set if the receiver sent a [Bye] first. */
        data class Closed(val code: Int, val reason: String, val byRemote: Boolean, val byeReason: String?) : Event
    }

    private val eventChannel = Channel<Event>(Channel.UNLIMITED)
    val events: Flow<Event> = eventChannel.receiveAsFlow()

    private val handshake = CompletableDeferred<ConnectResult>()
    private val used = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)

    @Volatile private var ws: Impl? = null
    @Volatile private var byeReason: String? = null

    suspend fun connect(endpoint: Endpoint, hello: Hello, timeoutMs: Long = 5_000): ConnectResult {
        check(used.compareAndSet(false, true)) { "SignalingClient is single-use" }
        val client = Impl(endpoint, hello, timeoutMs.toInt()).apply {
            isTcpNoDelay = true
            connectionLostTimeout = connectionLostTimeoutSec
        }
        ws = client
        try {
            client.connect() // Non-blocking: connects on the client's own thread.
            val result = withTimeoutOrNull(timeoutMs) { handshake.await() }
                ?: ConnectResult.Failed("No answer from $endpoint within ${timeoutMs} ms")
            if (result is ConnectResult.Connected) {
                connected.set(true)
            } else {
                client.close()
                eventChannel.close()
            }
            return result
        } catch (e: CancellationException) {
            client.close()
            eventChannel.close()
            throw e
        }
    }

    /** Returns false if the socket is not open. */
    fun send(message: SignalingMessage): Boolean {
        val c = ws ?: return false
        return try {
            if (!c.isOpen) return false
            c.send(SignalingCodec.encode(message))
            true
        } catch (e: WebsocketNotConnectedException) {
            false
        }
    }

    /** Sends a [Bye] (if open) and closes. Safe to call more than once. */
    fun close(reason: String = "disconnected by user") {
        val c = ws ?: return
        if (c.isOpen) send(Bye(reason))
        c.close(1000, reason)
    }

    private inner class Impl(
        endpoint: Endpoint,
        private val hello: Hello,
        connectTimeoutMs: Int,
    ) : WebSocketClient(endpoint.toUri(), Draft_6455(), emptyMap(), connectTimeoutMs) {

        override fun onOpen(handshakedata: ServerHandshake) {
            send(SignalingCodec.encode(hello))
        }

        override fun onMessage(message: String) {
            val decoded = SignalingCodec.decode(message) ?: return // Unknown type: ignore.
            if (!handshake.isCompleted) {
                handshake.complete(
                    when (decoded) {
                        is Welcome -> ConnectResult.Connected(decoded)
                        is Reject -> ConnectResult.Rejected(decoded.reason, decoded.receiverProtocolVersion)
                        else -> ConnectResult.Failed("Unexpected first message: ${decoded::class.simpleName}")
                    },
                )
                return
            }
            if (decoded is Bye) byeReason = decoded.reason
            eventChannel.trySend(Event.MessageReceived(decoded))
        }

        override fun onClose(code: Int, reason: String?, remote: Boolean) {
            handshake.complete(ConnectResult.Failed("Connection closed before welcome (code $code ${reason.orEmpty()})".trim()))
            if (connected.get()) {
                eventChannel.trySend(Event.Closed(code, reason.orEmpty(), remote, byeReason))
            }
            eventChannel.close()
        }

        override fun onError(ex: Exception) {
            // onClose always follows. Record the cause if we were still handshaking.
            handshake.complete(ConnectResult.Failed(ex.message ?: ex::class.java.simpleName, ex))
        }
    }
}
