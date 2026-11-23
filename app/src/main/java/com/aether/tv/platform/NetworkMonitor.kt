package com.aether.tv.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

enum class NetworkType { Wifi, Ethernet, Other, None }

/**
 * Real connection state from ConnectivityManager. [signalLevel] is 0..4, or null if not reported. [ipAddress] is this
 * TV's address on the network it uses (see [IpAddresses.pick]), or null when it has none or doesn't report one.
 */
data class NetworkState(val type: NetworkType, val signalLevel: Int?, val validated: Boolean, val ipAddress: String? = null) {
    val label: String get() = when (type) {
        NetworkType.Wifi -> if (validated) "Wi-Fi connected" else "Wi-Fi (no internet)"
        NetworkType.Ethernet -> if (validated) "Ethernet connected" else "Ethernet (no internet)"
        NetworkType.Other -> "Connected"
        NetworkType.None -> "Offline"
    }
    companion object { val Unknown = NetworkState(NetworkType.None, null, false) }
}

/** Choosing the address to show. Pure (unit-tested). */
object IpAddresses {
    /**
     * The address other devices on the home network reach this TV at: IPv4 first (what `adb connect` and most
     * routers show), else a global IPv6. Never loopback, link-local (fe80::, 169.254.x.x), wildcard or multicast.
     */
    fun pick(addresses: List<InetAddress>): String? {
        val usable = addresses.filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress && !it.isMulticastAddress }
        usable.firstOrNull { it is Inet4Address }?.hostAddress?.let { return it }
        return usable.firstOrNull { it is Inet6Address }?.hostAddress?.substringBefore('%')
    }

    /** `adb connect` target for [ip]: IPv6 literals need brackets before the port. */
    fun adbTarget(ip: String, port: Int = 5555): String = if (':' in ip) "[$ip]:$port" else "$ip:$port"
}

@Singleton
class NetworkMonitor @Inject constructor(@ApplicationContext private val context: Context) {
    private val cm get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun current(): NetworkState {
        val n = cm.activeNetwork ?: return NetworkState(NetworkType.None, null, false)
        return toState(cm.getNetworkCapabilities(n), runCatching { cm.getLinkProperties(n) }.getOrNull())
    }

    private fun toState(caps: NetworkCapabilities?, link: LinkProperties?): NetworkState {
        caps ?: return NetworkState(NetworkType.None, null, false)
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.Wifi
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.Ethernet
            else -> NetworkType.Other
        }
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val level = if (type == NetworkType.Wifi) wifiLevel(caps) else null
        val ip = link?.linkAddresses?.let { la -> IpAddresses.pick(la.map { it.address }) }
        return NetworkState(type, level, validated, ip)
    }

    @Suppress("DEPRECATION")
    private fun wifiLevel(caps: NetworkCapabilities): Int? {
        val rssi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) caps.signalStrength.takeIf { it != Int.MIN_VALUE }
        else runCatching { (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo.rssi }.getOrNull()
        return rssi?.let { WifiManager.calculateSignalLevel(it, 5) }
    }

    fun observe(): Flow<NetworkState> = callbackFlow {
        trySend(current())
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(toState(caps, runCatching { cm.getLinkProperties(network) }.getOrNull()))
            }
            // A new address (DHCP lease, router change) arrives here, not as a capabilities change.
            override fun onLinkPropertiesChanged(network: Network, link: LinkProperties) {
                trySend(toState(runCatching { cm.getNetworkCapabilities(network) }.getOrNull(), link))
            }
            override fun onLost(network: Network) { trySend(current()) }
            override fun onAvailable(network: Network) { trySend(current()) }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
        awaitClose { runCatching { cm.unregisterNetworkCallback(cb) } }
    }.distinctUntilChanged()
}
