package com.wuming.android.tv.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import com.wuming.android.tv.App
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

open class NetworkChecker {
    fun interface Callback {
        fun onResult(result: Result)
    }

    @JvmRecord data class Result(
        val available: Boolean,
        val address: String?,
    ) {
        fun hasAddress(): Boolean = address != null
    }

    private data class Probe(
        val name: String,
        val address: String,
        val port: Int,
    )

    private data class ActiveIPv6(
        val network: Network,
        val address: String?,
    )

    companion object {
        private const val TAG = "NetworkChecker"
        private const val CONNECT_TIMEOUT_MS = 2500
        private val PROBES = arrayOf(Probe("AliDNS", "2400:3200::1", 443), Probe("Cloudflare", "2606:4700:4700::1111", 443))

        private fun getActiveIPv6(): ActiveIPv6? {
            try {
                val manager = App.get().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
                val network = manager?.activeNetwork
                val properties = if (network == null) null else manager.getLinkProperties(network)
                if (properties == null) return null
                for (linkAddress in properties.linkAddresses) {
                    val address = linkAddress.address
                    if (address is Inet6Address && !address.isLoopbackAddress &&
                        !address.isLinkLocalAddress
                    ) {
                        return ActiveIPv6(network!!, address.hostAddress)
                    }
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "Error checking IPv6", e)
            }
            return null
        }

        /** Runs an isolated bounded probe only when explicitly requested by the UI. */
        @JvmStatic fun testIPv6Connectivity(callback: Callback) {
            val thread = Thread({ callback.onResult(runIPv6Probe()) }, "IPv6-probe")
            thread.isDaemon = true
            thread.start()
        }

        private fun runIPv6Probe(): Result {
            val active = getActiveIPv6() ?: return Result(false, null)
            for (probe in PROBES) {
                try {
                    Socket().use { socket ->
                        val target = InetAddress.getByName(probe.address)
                        active.network.bindSocket(socket)
                        socket.connect(InetSocketAddress(target, probe.port), CONNECT_TIMEOUT_MS)
                        AppLog.i(TAG, "IPv6 probe succeeded via " + probe.name)
                        return Result(true, active.address)
                    }
                } catch (e: Exception) {
                    AppLog.w(TAG, "IPv6 probe failed via " + probe.name + ": " + e.message)
                }
            }
            return Result(false, active.address)
        }
    }
}
