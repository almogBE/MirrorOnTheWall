package com.mirroronthewall.sender

import android.app.Application
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mirroronthewall.common.MirrorConstants
import com.mirroronthewall.common.protocol.Hello
import com.mirroronthewall.common.protocol.Ping
import com.mirroronthewall.common.protocol.Pong
import com.mirroronthewall.common.protocol.RejectReason
import com.mirroronthewall.common.signaling.Endpoint
import com.mirroronthewall.common.signaling.SignalingClient
import com.mirroronthewall.sender.discovery.DiscoveredReceiver
import com.mirroronthewall.sender.discovery.NsdDiscovery
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Phase 1: discovery plus a signaling-only session (hello/welcome, pairing, ping).
 *
 * The session lives in this ViewModel for now, so it ends when the activity is
 * finished. Phase 2 moves it into the MediaProjection foreground service so that
 * mirroring keeps running while the user is in other apps.
 */
class SenderViewModel(app: Application) : AndroidViewModel(app) {

    /** Where to connect: a discovered receiver or a manually entered address. */
    data class Target(val name: String, val endpoint: Endpoint, val receiverId: String?)

    sealed interface DiscoveryUi {
        data object Searching : DiscoveryUi
        data class Found(val receivers: List<DiscoveredReceiver>) : DiscoveryUi
        data class Failed(val message: String) : DiscoveryUi
    }

    sealed interface ConnectionUi {
        data object Idle : ConnectionUi
        data class Connecting(val target: Target) : ConnectionUi
        data class NeedsCode(val target: Target, val previousCodeWasWrong: Boolean) : ConnectionUi
        data class Connected(
            val target: Target,
            val receiverName: String,
            val tvDisplay: String,
            val newlyPaired: Boolean,
            val signalingRttMs: Double?,
        ) : ConnectionUi
        data class Error(val message: String) : ConnectionUi
    }

    private val prefs = SenderPrefs(app)
    private val discovery = NsdDiscovery(app)

    val discoveryUi: StateFlow<DiscoveryUi> = discovery.receivers()
        .map<List<DiscoveredReceiver>, DiscoveryUi> { DiscoveryUi.Found(it) }
        .catch { emit(DiscoveryUi.Failed(it.message ?: "Discovery failed")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiscoveryUi.Searching)

    private val _connection = MutableStateFlow<ConnectionUi>(ConnectionUi.Idle)
    val connection: StateFlow<ConnectionUi> = _connection.asStateFlow()

    val lastManualAddress: String? get() = prefs.lastManualAddress

    private var client: SignalingClient? = null
    private var sessionJob: Job? = null

    fun connect(receiver: DiscoveredReceiver) {
        val ep = receiver.endpoint ?: return
        connect(Target(receiver.displayName, ep, receiver.receiverId), code = null)
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
        endSession()
        _connection.value = ConnectionUi.Idle
    }

    fun dismissMessage() {
        if (_connection.value is ConnectionUi.Error || _connection.value is ConnectionUi.NeedsCode) {
            _connection.value = ConnectionUi.Idle
        }
    }

    override fun onCleared() {
        endSession()
    }

    private fun endSession() {
        sessionJob?.cancel()
        sessionJob = null
        client?.close()
        client = null
    }

    private fun connect(target: Target, code: String?) {
        endSession()
        val c = SignalingClient()
        client = c
        _connection.value = ConnectionUi.Connecting(target)
        sessionJob = viewModelScope.launch {
            val token = target.receiverId?.let(prefs::trustToken)
            val hello = Hello(MirrorConstants.PROTOCOL_VERSION, prefs.senderId, deviceName(), code, token)
            when (val r = c.connect(target.endpoint, hello)) {
                is SignalingClient.ConnectResult.Connected -> {
                    val w = r.welcome
                    prefs.saveTrustToken(w.receiverId, w.trustToken)
                    prefs.rememberHost(target.endpoint.host, w.receiverId)
                    val connected = ConnectionUi.Connected(
                        target = target.copy(receiverId = w.receiverId),
                        receiverName = w.receiverName,
                        tvDisplay = "${w.display.widthPx}×${w.display.heightPx} @ ${"%.0f".format(w.display.refreshHz)} Hz",
                        newlyPaired = code != null,
                        signalingRttMs = null,
                    )
                    _connection.value = connected
                    runSession(c, connected)
                }
                is SignalingClient.ConnectResult.Rejected -> _connection.value = when (r.reason) {
                    RejectReason.PAIRING_REQUIRED -> {
                        target.receiverId?.let(prefs::forgetTrustToken) // Stale token, if any.
                        ConnectionUi.NeedsCode(target, previousCodeWasWrong = false)
                    }
                    RejectReason.BAD_CODE -> ConnectionUi.NeedsCode(target, previousCodeWasWrong = true)
                    RejectReason.BUSY -> ConnectionUi.Error("${target.name} is already showing another phone.")
                    RejectReason.VERSION_MISMATCH -> ConnectionUi.Error(
                        "${target.name} runs an incompatible version (TV protocol ${r.receiverProtocolVersion}, " +
                            "phone protocol ${MirrorConstants.PROTOCOL_VERSION}). Update both apps.",
                    )
                    RejectReason.PROTOCOL_ERROR -> ConnectionUi.Error("${target.name} rejected the connection (protocol error).")
                }
                is SignalingClient.ConnectResult.Failed -> _connection.value =
                    ConnectionUi.Error("Couldn't connect to ${target.name} (${target.endpoint}): ${r.message}")
            }
        }
    }

    private suspend fun runSession(c: SignalingClient, initial: ConnectionUi.Connected) = coroutineScope {
        val pinger = launch {
            var seq = 0L
            while (isActive) {
                c.send(Ping(++seq, System.nanoTime()))
                delay(1_000)
            }
        }
        var current = initial
        c.events.collect { e ->
            when (e) {
                is SignalingClient.Event.MessageReceived -> {
                    val m = e.message
                    if (m is Pong) {
                        current = current.copy(signalingRttMs = (System.nanoTime() - m.senderTimeNanos) / 1_000_000.0)
                        _connection.value = current
                    }
                }
                is SignalingClient.Event.Closed -> {
                    _connection.value = ConnectionUi.Error(
                        e.byeReason?.let { "${current.receiverName} ended the session: $it" }
                            ?: "Connection to ${current.receiverName} lost (code ${e.code}).",
                    )
                }
            }
        }
        pinger.cancel()
    }

    private fun deviceName(): String =
        Settings.Global.getString(getApplication<Application>().contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"
}
