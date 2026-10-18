package com.wuming.android.tv.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.util.concurrent.atomic.AtomicLong

/** Process-wide generation of material network changes used by active transfers. */
object NetworkEnvironmentMonitor {
    private val LOCK = Any()
    private val GENERATION = AtomicLong(1L)
    private var started = false
    private var fingerprint = "uninitialized"

    @JvmStatic
    fun start(context: Context) {
        synchronized(LOCK) {
            if (started) return
            started = true
            update(PlatformCapabilities.activeNetworkCapabilities(context))
            try {
                if (!PlatformCapabilities.registerDefaultNetworkCallback(context,
                        object : ConnectivityManager.NetworkCallback() {
                            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                                update(capabilities)
                            }

                            override fun onLost(network: Network) {
                                update(null)
                            }
                        })) {
                    started = false
                }
            } catch (unavailable: RuntimeException) {
                started = false
            }
        }
    }

    @JvmStatic
    fun generation(): Long = GENERATION.get()

    private fun update(capabilities: NetworkCapabilities?) {
        val next = fingerprint(capabilities)
        synchronized(LOCK) {
            if (next == fingerprint) return
            fingerprint = next
            GENERATION.incrementAndGet()
        }
    }

    @JvmStatic
    @JvmName("fingerprint")
    internal fun fingerprint(capabilities: NetworkCapabilities?): String {
        if (capabilities == null) return "none"
        return fingerprint(transport(capabilities),
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING),
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            capabilities.linkDownstreamBandwidthKbps)
    }

    @JvmStatic
    @JvmName("fingerprint")
    internal fun fingerprint(transport: String, notMetered: Boolean,
                             notRoaming: Boolean, vpn: Boolean, downstreamKbps: Int): String =
        "$transport|$notMetered|$notRoaming|$vpn|${bandwidthBucket(downstreamKbps)}"

    private fun transport(capabilities: NetworkCapabilities): String {
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "wifi"
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "ethernet"
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "cellular"
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) return "bluetooth"
        return "other"
    }

    private fun bandwidthBucket(kbps: Int): Int {
        if (kbps <= 0) return 0
        val highest = Integer.highestOneBit(kbps)
        val next = if (highest > Int.MAX_VALUE / 2) highest else highest shl 1
        return if (kbps - highest < next - kbps) highest else next
    }
}
