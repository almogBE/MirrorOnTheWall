package com.mirroronthewall.sender

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mirroronthewall.sender.SenderSession.ConnectionUi
import com.mirroronthewall.sender.discovery.DiscoveredReceiver
import com.mirroronthewall.sender.discovery.NsdDiscovery
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * UI-facing state. Discovery runs only while the UI is visible. The session itself
 * lives in [SenderSession] (application scope), so it survives the activity.
 */
class SenderViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface DiscoveryUi {
        data object Searching : DiscoveryUi
        data class Found(val receivers: List<DiscoveredReceiver>) : DiscoveryUi
        data class Failed(val message: String) : DiscoveryUi
    }

    private val session = app.senderSession

    val connection: StateFlow<ConnectionUi> = session.connection

    val discoveryUi: StateFlow<DiscoveryUi> = NsdDiscovery(app).receivers()
        .map<List<DiscoveredReceiver>, DiscoveryUi> { DiscoveryUi.Found(it) }
        .catch { emit(DiscoveryUi.Failed(it.message ?: "Discovery failed")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiscoveryUi.Searching)

    val lastManualAddress: String? get() = session.lastManualAddress

    fun connect(receiver: DiscoveredReceiver) {
        val ep = receiver.endpoint ?: return
        session.connect(SenderSession.Target(receiver.displayName, ep, receiver.receiverId))
    }

    fun connectManual(address: String, code: String?): Boolean = session.connectManual(address, code)
    fun submitCode(code: String) = session.submitCode(code)
    fun disconnect() = session.disconnect()
    fun dismissMessage() = session.dismissMessage()
    fun stopMirroring() = session.stopMirroring("Mirroring stopped.")
}
