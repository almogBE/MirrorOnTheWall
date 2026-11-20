package com.mirroronthewall.receiver

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Display
import com.mirroronthewall.common.MirrorConstants
import com.mirroronthewall.common.pairing.PairingAuthority
import com.mirroronthewall.common.protocol.DisplayInfo
import com.mirroronthewall.common.protocol.IceCandidate
import com.mirroronthewall.common.protocol.Offer
import com.mirroronthewall.common.protocol.RejectReason
import com.mirroronthewall.receiver.media.ProxyVideoSink
import com.mirroronthewall.receiver.media.ReceiverMediaSession
import com.mirroronthewall.receiver.media.ReceiverWebRtc
import com.mirroronthewall.common.signaling.SignalingServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.PeerConnection

/**
 * Receiver-side composition of the signaling server, pairing, NSD advertising
 * and address monitoring. Owned by [MainActivity]: [start] and [stop] follow the
 * activity's started state, because the TV only mirrors while the app is on screen.
 *
 * Each signaling session gets one [ReceiverMediaSession]: the phone's offer is
 * answered and the remote video flows into [videoSink], which the activity points at
 * its renderer.
 */
class ReceiverController(
    private val context: Context,
    uiScope: CoroutineScope,
) {
    data class UiState(
        val receiverName: String,
        val server: SignalingServer.State,
        val ipv4: String?,
        val pairingCode: String,
        val nsd: NsdAdvertiser.Status,
        val peer: SignalingServer.Peer?,
        val notice: String?,
        /** WebRTC connection state of the current session, null before an offer arrives. */
        val video: PeerConnection.PeerConnectionState?,
    )

    private val prefs = ReceiverPrefs(context)
    private val pairing = PairingAuthority(prefs)
    private val receiverName = deviceName()
    private val server = SignalingServer(
        preferredPort = MirrorConstants.DEFAULT_SIGNALING_PORT,
        pairing = pairing,
        identity = { SignalingServer.ReceiverIdentity(prefs.receiverId, receiverName, displayInfo()) },
    )
    private val advertiser = NsdAdvertiser(context)
    private val addresses = LocalAddressMonitor(context)
    private val notice = MutableStateFlow<String?>(null)
    private val videoState = MutableStateFlow<PeerConnection.PeerConnectionState?>(null)

    private val webRtc = ReceiverWebRtc(context)

    /** EGL context to init the SurfaceViewRenderer with (shared with the decoder). */
    val eglContext: EglBase.Context get() = webRtc.eglBase.eglBaseContext

    /** Point [ProxyVideoSink.target] at the renderer while it's on screen. */
    val videoSink = ProxyVideoSink()

    // Touched only on ioScope's single thread.
    private var media: ReceiverMediaSession? = null

    // Server start/stop block on sockets: run them in order on one background thread.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private var advertiseJob: Job? = null

    val uiState: StateFlow<UiState> = combine(
        server.state,
        addresses.ipv4,
        pairing.pairingCode,
        advertiser.status,
        combine(server.activePeer, notice, videoState, ::Triple),
    ) { serverState, ip, code, nsd, (peer, note, video) ->
        UiState(receiverName, serverState, ip, code, nsd, peer, note, video)
    }.stateIn(
        uiScope,
        SharingStarted.Eagerly,
        UiState(receiverName, SignalingServer.State.Stopped, null, pairing.pairingCode.value, NsdAdvertiser.Status.Idle, null, null, null),
    )

    init {
        ioScope.launch {
            server.events.collect(::onServerEvent)
        }
    }

    // start/stop only enqueue work on ioScope (one thread), so the server, the
    // advertiser and advertiseJob are only touched in order, from that thread.
    fun start() {
        addresses.start()
        ioScope.launch {
            server.start()
            advertiseJob?.cancelAndJoin()
            // Advertise exactly while the server listens, on the port it actually got.
            advertiseJob = ioScope.launch {
                server.state.collect { st ->
                    if (st is SignalingServer.State.Listening) {
                        advertiser.register(st.port, receiverName, prefs.receiverId)
                    } else {
                        advertiser.unregister()
                    }
                }
            }
        }
    }

    fun stop() {
        addresses.stop()
        ioScope.launch {
            advertiseJob?.cancelAndJoin()
            advertiseJob = null
            advertiser.unregister()
            server.stop()
        }
    }

    /** Final cleanup. The controller can't be used afterwards. */
    fun release() {
        ioScope.launch {
            server.close()
            endMedia()
            webRtc.release()
        }.invokeOnCompletion { ioScope.cancel() }
    }

    private fun endMedia() {
        media?.dispose()
        media = null
        videoState.value = null
    }

    private suspend fun onServerEvent(event: SignalingServer.Event) {
        when (event) {
            is SignalingServer.Event.SessionStarted -> {
                endMedia()
                media = ReceiverMediaSession(
                    webRtc = webRtc,
                    sink = videoSink,
                    send = server::send,
                    onState = { videoState.value = it },
                )
                val p = event.peer
                notice.value = if (p.newlyPaired) "Paired with ${p.senderName}" else null
                Log.i(TAG, "Session ${p.sessionId} started: ${p.senderName} (${p.remoteAddress})")
            }
            is SignalingServer.Event.SessionEnded -> {
                // A replaced session's end arrives before the new session's start, so
                // this never tears down the new session's media.
                endMedia()
                notice.value = "${event.peer.senderName} disconnected: ${event.reason}"
                Log.i(TAG, "Session ${event.peer.sessionId} ended: ${event.reason}")
            }
            is SignalingServer.Event.SenderRejected -> {
                val who = event.senderName ?: event.remoteAddress
                notice.value = when (event.reason) {
                    RejectReason.BAD_CODE -> "$who entered a wrong pairing code"
                    RejectReason.PAIRING_REQUIRED -> "$who needs the pairing code shown below"
                    RejectReason.BUSY -> "$who tried to connect, but another phone is connected"
                    RejectReason.VERSION_MISMATCH -> "$who runs an incompatible app version"
                    RejectReason.PROTOCOL_ERROR -> "Rejected an invalid connection from $who"
                }
            }
            is SignalingServer.Event.MessageReceived -> when (val m = event.message) {
                is Offer -> {
                    val session = media ?: return
                    try {
                        session.onOffer(m.sdp)
                    } catch (e: Exception) {
                        Log.e(TAG, "Could not answer the offer", e)
                        notice.value = "Could not start video: ${e.message}"
                        server.endSession("receiver could not accept the video: ${e.message}")
                    }
                }
                is IceCandidate -> media?.onRemoteIce(m)
                else -> Log.i(TAG, "Ignoring ${m::class.simpleName}")
            }
        }
    }

    private fun deviceName(): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    private fun displayInfo(): DisplayInfo {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val mode = display?.mode
        return DisplayInfo(
            widthPx = mode?.physicalWidth ?: 0,
            heightPx = mode?.physicalHeight ?: 0,
            refreshHz = mode?.refreshRate ?: 0f,
        )
    }

    private companion object {
        const val TAG = "ReceiverController"
    }
}
