package com.wuming.tv.core.playback

import java.util.concurrent.Callable

/** Waits for the native downloader to publish its loopback URL. */
object LocalMediaUrlWaiter {
    fun interface Sleeper {
        @Throws(InterruptedException::class)
        fun sleep()
    }

    @JvmStatic
    @Throws(Exception::class)
    fun await(attempts: Int, probe: Callable<String?>, sleeper: Sleeper): String? {
        require(attempts > 0)
        repeat(attempts) { index ->
            val url = probe.call()
            if (!url.isNullOrBlank()) return url
            if (index + 1 < attempts) sleeper.sleep()
        }
        return null
    }
}
