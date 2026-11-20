package com.mirroronthewall.receiver

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address

/**
 * Tracks the TV's current LAN IPv4 address on the default network (Wi-Fi or
 * Ethernet). The address is shown on screen for manual entry on the phone.
 */
class LocalAddressMonitor(context: Context) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _ipv4 = MutableStateFlow<String?>(null)

    /** Null while there is no network with an IPv4 address. */
    val ipv4: StateFlow<String?> = _ipv4.asStateFlow()

    private var callback: ConnectivityManager.NetworkCallback? = null

    @Synchronized
    fun start() {
        if (callback != null) return
        // Initial value, so the screen doesn't flash "no network" before the first callback.
        _ipv4.value = cm.activeNetwork?.let { cm.getLinkProperties(it) }?.let(::pickIpv4)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                _ipv4.value = pickIpv4(linkProperties)
            }

            override fun onLost(network: Network) {
                _ipv4.value = null
            }
        }
        callback = cb
        cm.registerDefaultNetworkCallback(cb)
    }

    @Synchronized
    fun stop() {
        callback?.let {
            try {
                cm.unregisterNetworkCallback(it)
            } catch (e: IllegalArgumentException) {
                // Already unregistered.
            }
        }
        callback = null
    }

    private fun pickIpv4(lp: LinkProperties): String? =
        lp.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
}
