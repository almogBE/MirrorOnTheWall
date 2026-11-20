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
import com.mirroronthewall.common.protocol.RejectReason
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

/**
 * Receiver-side composition of the signaling server, pairing, NSD advertising
 * and address monitoring. Owned by [MainActivity]: [start] and [stop] follow the
 * activity's started state, because the TV only mirrors while the app is on screen.
 *
 * Phase 1 scope: sessions are established and torn down, and Ping/Pong works.
 * Media (offer/answer, rendering) arrives in Phase 2.
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

    // Server start/stop block on sockets: run them in order on one background thread.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private var advertiseJob: Job? = null

    val uiState: StateFlow<UiState> = combine(
        server.state,
        addresses.ipv4,
        pairing.pairingCode,
        advertiser.status,
        combine(server.activePeer, notice, ::Pair),
    ) { serverState, ip, code, nsd, (peer, note) ->
        UiState(receiverName, serverState, ip, code, nsd, peer, note)
    }.stateIn(
        uiScope,
        SharingStarted.Eagerly,
        UiState(receiverName, SignalingServer.State.Stopped, null, pairing.pairingCode.value, NsdAdvertiser.Status.Idle, null, null),
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
        ioScope.launch { server.close() }.invokeOnCompletion { ioScope.cancel() }
    }

    private fun onServerEvent(event: SignalingServer.Event) {
        when (event) {
            is SignalingServer.Event.SessionStarted -> {
                val p = event.peer
                notice.value = if (p.newlyPaired) "Paired with ${p.senderName}" else null
                Log.i(TAG, "Session ${p.sessionId} started: ${p.senderName} (${p.remoteAddress})")
            }
            is SignalingServer.Event.SessionEnded -> {
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
            is SignalingServer.Event.MessageReceived -> {
                // Phase 1: no media yet. Offer/ICE handling arrives in Phase 2.
                Log.i(TAG, "Ignoring ${event.message::class.simpleName} (media not implemented yet)")
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
