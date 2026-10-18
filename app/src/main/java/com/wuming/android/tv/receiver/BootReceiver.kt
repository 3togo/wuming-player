package com.wuming.android.tv.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import com.wuming.android.tv.App
import com.wuming.android.tv.api.config.LiveConfig
import java.util.concurrent.atomic.AtomicBoolean

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        registerCallback()
    }

    private fun registerCallback() {
        val manager = App.get().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (manager != null) {
            val callback = Callback(manager)
            manager.registerDefaultNetworkCallback(callback)
            callback.scheduleTimeout()
        }
    }

    internal class Callback(private val manager: ConnectivityManager) : ConnectivityManager.NetworkCallback() {
        private val handled = AtomicBoolean()
        private val timeoutHandler = Handler(Looper.getMainLooper())
        private val timeoutRunnable = Runnable {
            if (!handled.compareAndSet(false, true)) return@Runnable
            try {
                manager.unregisterNetworkCallback(this)
            } catch (ignored: Exception) {
            }
        }

        override fun onAvailable(network: Network) {
            if (!handled.compareAndSet(false, true)) return
            timeoutHandler.removeCallbacks(timeoutRunnable)
            try {
                LiveConfig.get().init().load()
            } finally {
                try {
                    manager.unregisterNetworkCallback(this)
                } catch (ignored: Exception) {
                }
            }
        }

        fun scheduleTimeout() {
            timeoutHandler.postDelayed(timeoutRunnable, TIMEOUT_MS)
        }

        companion object {
            private const val TIMEOUT_MS = 30_000L
        }
    }
}
