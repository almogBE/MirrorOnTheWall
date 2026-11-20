package com.mirroronthewall.receiver

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.mirroronthewall.common.MirrorConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Advertises the signaling server as `_lowmirror._tcp` over NSD/mDNS.
 *
 * NSD runs in a system service, so no MulticastLock is needed. If another device
 * already uses our service name, NSD renames ours (e.g. "TV (2)"), and [status]
 * reports the final name.
 */
class NsdAdvertiser(context: Context) {

    sealed interface Status {
        data object Idle : Status
        data object Registering : Status
        data class Registered(val serviceName: String) : Status
        data class Failed(val errorCode: Int) : Status
    }

    private val nsd = context.getSystemService(NsdManager::class.java)
    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private var listener: NsdManager.RegistrationListener? = null

    @Synchronized
    fun register(port: Int, receiverName: String, receiverId: String) {
        unregister()
        val info = NsdServiceInfo().apply {
            serviceName = truncateUtf8(receiverName, MAX_SERVICE_NAME_BYTES)
            serviceType = MirrorConstants.NSD_SERVICE_TYPE
            setPort(port)
            setAttribute(MirrorConstants.Txt.PROTOCOL_VERSION, MirrorConstants.PROTOCOL_VERSION.toString())
            setAttribute(MirrorConstants.Txt.RECEIVER_ID, receiverId)
            setAttribute(MirrorConstants.Txt.DISPLAY_NAME, truncateUtf8(receiverName, MAX_TXT_VALUE_BYTES))
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                if (listener === this) _status.value = Status.Registered(serviceInfo.serviceName)
                Log.i(TAG, "Registered '${serviceInfo.serviceName}' on port $port")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Registration failed: $errorCode")
                synchronized(this@NsdAdvertiser) {
                    if (listener === this) {
                        listener = null
                        _status.value = Status.Failed(errorCode)
                    }
                }
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Unregistration failed: $errorCode")
            }
        }
        listener = l
        _status.value = Status.Registering
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    }

    @Synchronized
    fun unregister() {
        val l = listener ?: return
        listener = null
        try {
            nsd.unregisterService(l)
        } catch (e: IllegalArgumentException) {
            // Listener was never registered (registration failed): nothing to undo.
        }
        _status.value = Status.Idle
    }

    private companion object {
        const val TAG = "NsdAdvertiser"
        const val MAX_SERVICE_NAME_BYTES = 63 // DNS label limit
        const val MAX_TXT_VALUE_BYTES = 200

        fun truncateUtf8(s: String, maxBytes: Int): String {
            if (s.toByteArray(Charsets.UTF_8).size <= maxBytes) return s
            var end = s.length
            while (end > 0 && s.substring(0, end).toByteArray(Charsets.UTF_8).size > maxBytes) end--
            // Don't split a surrogate pair.
            if (end > 0 && Character.isHighSurrogate(s[end - 1])) end--
            return s.substring(0, end)
        }
    }
}
