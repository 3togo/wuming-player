package com.wuming.android.tv.server

import com.wuming.android.tv.service.PlaybackService
import com.wuming.android.tv.utils.AppLog
import com.wuming.android.tv.utils.Task
import com.github.catvod.Proxy
import com.github.catvod.utils.Util

class Server {

    @Volatile
    var serviceRef: PlaybackService? = null

    @Volatile
    var nano: Nano? = null

    @Volatile
    var state: Int = STATE_STOPPED

    var service: PlaybackService?
        get() = serviceRef
        set(value) { serviceRef = value }

    fun getAddress(): String = getAddress(false)

    fun getAddress(tab: Int): String = RemoteAccess.secureWebAddress(getAddress(false), tab)

    fun getAddress(path: String): String = getAddress(true) + path

    fun getAddress(local: Boolean): String =
        "http://" + (if (local) "127.0.0.1" else Util.getIp()) + ":" + Proxy.getPort()

    @Synchronized
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    fun start() {
        if (state == STATE_STARTED || state == STATE_STARTING) return
        while (state == STATE_STOPPING) {
            try { synchronized(this) { (this as java.lang.Object).wait(50) } } catch (e: InterruptedException) { Thread.currentThread().interrupt(); return }
        }
        state = STATE_STARTING
        for (i in PORT_RANGE_START until PORT_RANGE_END) {
            try {
                nano = Nano(i)
                nano!!.start(5000)
                Proxy.set(i)
                state = STATE_STARTED
                break
            } catch (e: Throwable) {
                AppLog.d(TAG, "Port $i unavailable, trying next", e)
                if (nano != null) { try { nano!!.stop() } catch (_: Exception) {} }
                nano = null
            }
        }
        if (state == STATE_STARTING) state = STATE_STOPPED
        synchronized(this) { (this as java.lang.Object).notifyAll() }
    }

    @Synchronized
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    fun stop() {
        if (state == STATE_STOPPED || state == STATE_STOPPING) return
        val current = nano
        serviceRef = null
        nano = null
        state = STATE_STOPPING
        Task.execute {
            try {
                current?.stop()
            } finally {
                synchronized<Unit>(this@Server) {
                    state = STATE_STOPPED
                    (this@Server as java.lang.Object).notifyAll()
                }
            }
        }
    }

    companion object {
        private const val TAG = "Server"
        private const val PORT_RANGE_START = 9978
        private const val PORT_RANGE_END = 9999

        private const val STATE_STOPPED = 0
        private const val STATE_STARTING = 1
        private const val STATE_STARTED = 2
        private const val STATE_STOPPING = 3

        @Volatile
        private var INSTANCE: Server = Server()

        @JvmStatic
        fun get(): Server = INSTANCE
    }
}
