package com.mirroronthewall.sender.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.mirroronthewall.common.MirrorConstants
import com.mirroronthewall.common.signaling.Endpoint
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** A receiver seen on the LAN. [endpoint] is null until its address is resolved. */
data class DiscoveredReceiver(
    val serviceName: String,
    val displayName: String,
    val receiverId: String?,
    val protocolVersion: Int?,
    val endpoint: Endpoint?,
) {
    val isCompatible: Boolean get() = protocolVersion == null || protocolVersion == MirrorConstants.PROTOCOL_VERSION
}

/**
 * Discovers `_lowmirror._tcp` receivers with NsdManager. No MulticastLock is needed,
 * because NSD runs in the system service.
 *
 * Resolving:
 * - API 34+: `registerServiceInfoCallback`, which also reports later address or port
 *   changes (e.g. the TV got a new DHCP lease).
 * - API 29–33: `resolveService`, which allows only one resolve at a time, so
 *   requests are queued.
 */
class NsdDiscovery(context: Context) {

    private val nsd = context.getSystemService(NsdManager::class.java)

    /** Current list of receivers, updated live. Discovery runs while the flow is collected. */
    fun receivers(): Flow<List<DiscoveredReceiver>> = callbackFlow {
        val lock = Any()
        val found = LinkedHashMap<String, DiscoveredReceiver>()

        fun publish() {
            trySend(synchronized(lock) { found.values.toList() })
        }

        fun onResolved(info: NsdServiceInfo, addresses: List<InetAddress>) {
            val parsed = parse(info, addresses)
            synchronized(lock) {
                // Ignore late results for services that were lost in the meantime.
                if (found.containsKey(info.serviceName)) found[info.serviceName] = parsed
            }
            publish()
        }

        val resolver: Resolver =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                CallbackResolver(nsd, ::onResolved)
            } else {
                LegacyResolver(nsd, ::onResolved)
            }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close(IllegalStateException("NSD discovery failed to start (error $errorCode)"))
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val name = serviceInfo.serviceName
                synchronized(lock) {
                    if (!found.containsKey(name)) {
                        found[name] = DiscoveredReceiver(name, name, null, null, null)
                    }
                }
                publish()
                resolver.resolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                synchronized(lock) { found.remove(serviceInfo.serviceName) }
                resolver.forget(serviceInfo.serviceName)
                publish()
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        publish()
        nsd.discoverServices(MirrorConstants.NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)

        awaitClose {
            try {
                nsd.stopServiceDiscovery(listener)
            } catch (e: IllegalArgumentException) {
                // Discovery never started (start failed): nothing to stop.
            }
            resolver.close()
        }
    }.conflate()

    private interface Resolver {
        fun resolve(info: NsdServiceInfo)
        fun forget(serviceName: String)
        fun close()
    }

    /** API 34+: one ServiceInfoCallback per service, kept until the service is lost. */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private class CallbackResolver(
        private val nsd: NsdManager,
        private val onResolved: (NsdServiceInfo, List<InetAddress>) -> Unit,
    ) : Resolver {
        private val executor: ExecutorService = Executors.newSingleThreadExecutor()
        private val callbacks = HashMap<String, NsdManager.ServiceInfoCallback>()

        @Synchronized
        override fun resolve(info: NsdServiceInfo) {
            if (callbacks.containsKey(info.serviceName)) return
            val cb = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    Log.w(TAG, "ServiceInfoCallback registration failed for ${info.serviceName}: $errorCode")
                    synchronized(this@CallbackResolver) { callbacks.remove(info.serviceName) }
                }

                override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                    if (serviceInfo.hostAddresses.isNotEmpty()) onResolved(serviceInfo, serviceInfo.hostAddresses)
                }

                override fun onServiceLost() = Unit // DiscoveryListener.onServiceLost handles removal.
                override fun onServiceInfoCallbackUnregistered() = Unit
            }
            callbacks[info.serviceName] = cb
            nsd.registerServiceInfoCallback(info, executor, cb)
        }

        @Synchronized
        override fun forget(serviceName: String) {
            callbacks.remove(serviceName)?.let(::unregisterQuietly)
        }

        @Synchronized
        override fun close() {
            callbacks.values.forEach(::unregisterQuietly)
            callbacks.clear()
            executor.shutdown()
        }

        private fun unregisterQuietly(cb: NsdManager.ServiceInfoCallback) {
            try {
                nsd.unregisterServiceInfoCallback(cb)
            } catch (e: IllegalArgumentException) {
                // Registration had failed: nothing to undo.
            }
        }
    }

    /** API 29–33: serial resolveService queue (concurrent resolves fail with FAILURE_ALREADY_ACTIVE). */
    private class LegacyResolver(
        private val nsd: NsdManager,
        private val onResolved: (NsdServiceInfo, List<InetAddress>) -> Unit,
    ) : Resolver {
        private val queue = ArrayDeque<NsdServiceInfo>()
        private var inFlight: String? = null
        private var closed = false

        @Synchronized
        override fun resolve(info: NsdServiceInfo) {
            if (closed || inFlight == info.serviceName || queue.any { it.serviceName == info.serviceName }) return
            queue.addLast(info)
            pumpLocked()
        }

        @Synchronized
        override fun forget(serviceName: String) {
            queue.removeAll { it.serviceName == serviceName }
        }

        @Synchronized
        override fun close() {
            closed = true
            queue.clear()
        }

        private fun pumpLocked() {
            if (closed || inFlight != null) return
            val next = queue.removeFirstOrNull() ?: return
            inFlight = next.serviceName
            @Suppress("DEPRECATION") // Deprecated in API 34. This class only runs below 34.
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    val host = serviceInfo.host
                    done()
                    if (host != null) onResolved(serviceInfo, listOf(host))
                }

                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                    done()
                }
            })
        }

        private fun done() = synchronized(this) {
            inFlight = null
            pumpLocked()
        }
    }

    private companion object {
        const val TAG = "NsdDiscovery"

        fun parse(info: NsdServiceInfo, addresses: List<InetAddress>): DiscoveredReceiver {
            val attrs = info.attributes
            fun attr(key: String): String? = attrs[key]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
            // Prefer IPv4: it's what the TV shows on screen, and it avoids IPv6 scope-id issues.
            val address = addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull()
            val endpoint = address?.hostAddress?.let { host ->
                runCatching { Endpoint(host, info.port) }.getOrNull()
            }
            return DiscoveredReceiver(
                serviceName = info.serviceName,
                displayName = attr(MirrorConstants.Txt.DISPLAY_NAME) ?: info.serviceName,
                receiverId = attr(MirrorConstants.Txt.RECEIVER_ID),
                protocolVersion = attr(MirrorConstants.Txt.PROTOCOL_VERSION)?.toIntOrNull(),
                endpoint = endpoint,
            )
        }
    }
}
