package com.mirroronthewall.sender

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.mirroronthewall.common.MirrorConstants
import com.mirroronthewall.common.media.CaptureSizing
import com.mirroronthewall.common.media.VideoProfile
import com.mirroronthewall.common.protocol.Answer
import com.mirroronthewall.common.protocol.DisplayInfo
import com.mirroronthewall.common.protocol.Hello
import com.mirroronthewall.common.protocol.IceCandidate
import com.mirroronthewall.common.protocol.Offer
import com.mirroronthewall.common.protocol.Ping
import com.mirroronthewall.common.protocol.Pong
import com.mirroronthewall.common.protocol.RejectReason
import com.mirroronthewall.common.signaling.Endpoint
import com.mirroronthewall.common.signaling.SignalingClient
import com.mirroronthewall.sender.media.SenderMediaPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.PeerConnection

/**
 * The phone's one session with a TV: signaling, pairing, and (while mirroring) the
 * WebRTC pipeline.
 *
 * It lives in the Application, not the activity, so mirroring keeps running while
 * the user is in other apps. [MirrorService] keeps the process in the foreground
 * during that time. All state changes run on one dispatcher ([dispatcher]), so no
 * locking is needed.
 *
 * Stopping mirroring ends the session (with a Bye). Starting again reconnects
 * instantly with the stored trust token. Android 14+ requires a fresh consent per
 * capture anyway.
 */
class SenderSession(private val app: Application) {

    /** Where to connect: a discovered receiver or a manually entered address. */
    data class Target(val name: String, val endpoint: Endpoint, val receiverId: String?)

    sealed interface MirrorUi {
        data object Off : MirrorUi
        data object Starting : MirrorUi
        data class Live(val captureSize: CaptureSizing.Size, val fps: Int, val connection: String) : MirrorUi
    }

    sealed interface ConnectionUi {
        data object Idle : ConnectionUi
        data class Connecting(val target: Target) : ConnectionUi
        data class NeedsCode(val target: Target, val previousCodeWasWrong: Boolean) : ConnectionUi
        data class Connected(
            val target: Target,
            val receiverName: String,
            val tvDisplay: DisplayInfo,
            val newlyPaired: Boolean,
            val signalingRttMs: Double?,
            val mirror: MirrorUi,
        ) : ConnectionUi

        /** The session ended or never started. [isError] marks failures, as opposed to normal ends. */
        data class Message(val text: String, val isError: Boolean) : ConnectionUi
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val prefs = SenderPrefs(app)

    private val _connection = MutableStateFlow<ConnectionUi>(ConnectionUi.Idle)
    val connection: StateFlow<ConnectionUi> = _connection.asStateFlow()

    /** True from "Start mirroring" until the pipeline is torn down. [MirrorService] follows it. */
    val mirroringActive: StateFlow<Boolean> = connection
        .map { (it as? ConnectionUi.Connected)?.mirror.let { m -> m != null && m != MirrorUi.Off } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val lastManualAddress: String? get() = prefs.lastManualAddress

    // Session-thread state.
    private var client: SignalingClient? = null
    private var sessionJob: Job? = null
    private var pipeline: SenderMediaPipeline? = null

    // ------------------------------------------------------------ connection

    fun connect(target: Target, code: String? = null) {
        scope.launch { connectNow(target, code) }
    }

    /** Returns false if [address] can't be parsed. */
    fun connectManual(address: String, code: String?): Boolean {
        val ep = Endpoint.parse(address) ?: return false
        prefs.lastManualAddress = address.trim()
        connect(Target(ep.toString(), ep, prefs.receiverIdForHost(ep.host)), code?.takeIf { it.isNotBlank() })
        return true
    }

    fun submitCode(code: String) {
        val needs = _connection.value as? ConnectionUi.NeedsCode ?: return
        connect(needs.target, code)
    }

    fun disconnect() {
        scope.launch { endSession(ConnectionUi.Idle, byeReason = "disconnected by user") }
    }

    fun dismissMessage() {
        scope.launch {
            val c = _connection.value
            if (c is ConnectionUi.Message || c is ConnectionUi.NeedsCode) _connection.value = ConnectionUi.Idle
        }
    }

    // ------------------------------------------------------------- mirroring

    /**
     * Called by [MirrorService] **after** startForeground(mediaProjection). Returns
     * true if the pipeline started. Mirroring then runs until [mirroringActive]
     * turns false.
     */
    suspend fun startMirroring(serviceContext: Context, projectionData: Intent): Boolean = withContext(dispatcher) {
        val connected = _connection.value as? ConnectionUi.Connected
        val c = client
        if (connected == null || c == null || pipeline != null) {
            Log.w(TAG, "startMirroring ignored: connected=${connected != null} pipeline=${pipeline != null}")
            return@withContext false
        }
        updateConnected { it.copy(mirror = MirrorUi.Starting) }
        val p = SenderMediaPipeline(
            context = serviceContext,
            projectionData = projectionData,
            tvDisplay = connected.tvDisplay,
            profile = VideoProfile.SMOOTH,
            onLocalIce = { ice -> c.send(ice) },
            onProjectionStopped = {
                stopMirroring("Screen capture stopped (you stopped it, the screen locked, or another app started capturing).")
            },
            onConnectionState = { st -> scope.launch { onPeerState(st) } },
        )
        pipeline = p
        try {
            val offer = p.start()
            c.send(Offer(offer))
            val status = p.status.value
            updateConnected {
                it.copy(mirror = MirrorUi.Live(status?.captureSize ?: CaptureSizing.Size(0, 0), status?.fps ?: 0, "connecting"))
            }
            // Keep the displayed capture size current when the phone rotates.
            scope.launch {
                // Ends when the pipeline is disposed (status becomes null).
                p.status.takeWhile { it != null }.collect { s ->
                    if (s != null && pipeline === p) updateConnected { cur ->
                        val live = cur.mirror as? MirrorUi.Live ?: return@updateConnected cur
                        cur.copy(mirror = live.copy(captureSize = s.captureSize, fps = s.fps))
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Pipeline start failed", e)
            teardownMirroring("Couldn't start mirroring: ${e.message ?: e::class.java.simpleName}", isError = true)
            false
        }
    }

    /** Stops mirroring and ends the session. Safe from any thread. */
    fun stopMirroring(reason: String) {
        scope.launch { teardownMirroring(reason, isError = false) }
    }

    // ------------------------------------------------------------- internals

    private suspend fun connectNow(target: Target, code: String?) {
        endSession(ConnectionUi.Connecting(target), byeReason = "reconnecting")
        val c = SignalingClient()
        client = c
        val token = target.receiverId?.let(prefs::trustToken)
        val hello = Hello(MirrorConstants.PROTOCOL_VERSION, prefs.senderId, deviceName(), code, token)
        val r = c.connect(target.endpoint, hello)
        if (client !== c) {
            // Superseded while handshaking (user disconnected or picked another TV).
            if (r is SignalingClient.ConnectResult.Connected) c.close("superseded")
            return
        }
        when (r) {
            is SignalingClient.ConnectResult.Connected -> {
                val w = r.welcome
                prefs.saveTrustToken(w.receiverId, w.trustToken)
                prefs.rememberHost(target.endpoint.host, w.receiverId)
                _connection.value = ConnectionUi.Connected(
                    target = target.copy(receiverId = w.receiverId),
                    receiverName = w.receiverName,
                    tvDisplay = w.display,
                    newlyPaired = code != null,
                    signalingRttMs = null,
                    mirror = MirrorUi.Off,
                )
                sessionJob = scope.launch { runSession(c) }
            }
            is SignalingClient.ConnectResult.Rejected -> {
                client = null
                _connection.value = when (r.reason) {
                    RejectReason.PAIRING_REQUIRED -> {
                        target.receiverId?.let(prefs::forgetTrustToken) // Stale token, if any.
                        ConnectionUi.NeedsCode(target, previousCodeWasWrong = false)
                    }
                    RejectReason.BAD_CODE -> ConnectionUi.NeedsCode(target, previousCodeWasWrong = true)
                    RejectReason.BUSY -> ConnectionUi.Message("${target.name} is already showing another phone.", isError = true)
                    RejectReason.VERSION_MISMATCH -> ConnectionUi.Message(
                        "${target.name} runs an incompatible version (TV protocol ${r.receiverProtocolVersion}, " +
                            "phone protocol ${MirrorConstants.PROTOCOL_VERSION}). Update both apps.",
                        isError = true,
                    )
                    RejectReason.PROTOCOL_ERROR -> ConnectionUi.Message("${target.name} rejected the connection (protocol error).", isError = true)
                }
            }
            is SignalingClient.ConnectResult.Failed -> {
                client = null
                _connection.value = ConnectionUi.Message("Couldn't connect to ${target.name} (${target.endpoint}): ${r.message}", isError = true)
            }
        }
    }

    private suspend fun runSession(c: SignalingClient) = coroutineScope {
        val pinger = launch {
            var seq = 0L
            while (isActive) {
                c.send(Ping(++seq, System.nanoTime()))
                delay(1_000)
            }
        }
        c.events.collect { e ->
            when (e) {
                is SignalingClient.Event.MessageReceived -> when (val m = e.message) {
                    is Pong -> updateConnected { it.copy(signalingRttMs = (System.nanoTime() - m.senderTimeNanos) / 1_000_000.0) }
                    is Answer -> try {
                        pipeline?.onAnswer(m.sdp)
                    } catch (ex: Exception) {
                        Log.e(TAG, "Applying answer failed", ex)
                        teardownMirroring("The TV's answer was not accepted: ${ex.message}", isError = true)
                    }
                    is IceCandidate -> pipeline?.onRemoteIce(m)
                    else -> Unit
                }
                is SignalingClient.Event.Closed -> {
                    if (client === c) {
                        val name = (_connection.value as? ConnectionUi.Connected)?.receiverName ?: "TV"
                        disposePipeline()
                        client = null
                        _connection.value = ConnectionUi.Message(
                            e.byeReason?.let { "$name ended the session: $it" } ?: "Connection to $name lost (code ${e.code}).",
                            isError = e.byeReason == null,
                        )
                    }
                }
            }
        }
        pinger.cancel()
    }

    private fun onPeerState(st: PeerConnection.PeerConnectionState) {
        updateConnected { cur ->
            val live = cur.mirror as? MirrorUi.Live ?: return@updateConnected cur
            cur.copy(mirror = live.copy(connection = st.name.lowercase()))
        }
        // Phase 2: a failed media connection ends the session with a clear message.
        // Automatic ICE restart and reconnect arrive in Phase 3 (ARCHITECTURE.md §7.3).
        if (st == PeerConnection.PeerConnectionState.FAILED) {
            teardownMirroring("The video connection to the TV failed.", isError = true)
        }
    }

    private fun teardownMirroring(reason: String, isError: Boolean) {
        if (pipeline == null && _connection.value !is ConnectionUi.Connected) return
        endSession(ConnectionUi.Message(reason, isError), byeReason = "mirroring stopped")
    }

    /** Ends media and signaling, then shows [next]. */
    private fun endSession(next: ConnectionUi, byeReason: String) {
        disposePipeline()
        sessionJob?.cancel()
        sessionJob = null
        client?.close(byeReason)
        client = null
        _connection.value = next
    }

    private fun disposePipeline() {
        pipeline?.dispose()
        pipeline = null
    }

    private inline fun updateConnected(f: (ConnectionUi.Connected) -> ConnectionUi.Connected) {
        val cur = _connection.value as? ConnectionUi.Connected ?: return
        _connection.value = f(cur)
    }

    private fun deviceName(): String =
        Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"

    private companion object {
        const val TAG = "SenderSession"
    }
}
