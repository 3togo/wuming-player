package com.wuming.android.tv.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager

/** Central compatibility boundary for Android-version-dependent platform behavior. */
object PlatformCapabilities {
    @JvmStatic
    fun activeNetworkCapabilities(context: Context): NetworkCapabilities? {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = manager?.activeNetwork
        return network?.let { manager.getNetworkCapabilities(it) }
    }

    @JvmStatic
    fun isCellularRoaming(context: Context, capabilities: NetworkCapabilities): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
        }
        val manager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        return manager?.isNetworkRoaming == true
    }

    @JvmStatic
    fun registerDefaultNetworkCallback(
        context: Context, callback: ConnectivityManager.NetworkCallback
    ): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        manager.registerDefaultNetworkCallback(callback)
        return true
    }

    @JvmStatic
    fun unregisterNetworkCallback(
        context: Context, callback: ConnectivityManager.NetworkCallback?
    ) {
        if (callback == null) return
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        try {
            manager.unregisterNetworkCallback(callback)
        } catch (ignored: IllegalArgumentException) {
        }
    }
}
