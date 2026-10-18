package com.wuming.android.tv

import java.util.concurrent.TimeUnit

object Constant {
    @JvmField val INTERVAL_SEEK: Long = TimeUnit.SECONDS.toMillis(10)
    @JvmField val INTERVAL_HIDE: Long = TimeUnit.SECONDS.toMillis(5)
    @JvmField val TIMEOUT_VOD: Long = TimeUnit.SECONDS.toMillis(30)
    @JvmField val TIMEOUT_PLAYER: Long = TimeUnit.MINUTES.toMillis(5)
    @JvmField val TIMEOUT_LIVE: Long = TimeUnit.SECONDS.toMillis(30)
    @JvmField val TIMEOUT_EPG: Long = TimeUnit.SECONDS.toMillis(5)
    @JvmField val TIMEOUT_XML: Long = TimeUnit.SECONDS.toMillis(15)
    @JvmField val TIMEOUT_PLAY: Long = TimeUnit.SECONDS.toMillis(15)
    @JvmField val TIMEOUT_SYNC: Long = TimeUnit.SECONDS.toMillis(2)
    @JvmField val TIMEOUT_SEARCH: Long = TimeUnit.SECONDS.toMillis(30)
    @JvmField val TIMEOUT_PARSE_DEF: Long = TimeUnit.SECONDS.toMillis(15)
    @JvmField val TIMEOUT_PARSE_WEB: Long = TimeUnit.SECONDS.toMillis(15)
    @JvmField val TIMEOUT_PARSE_LIVE: Long = TimeUnit.SECONDS.toMillis(10)
    @JvmField val HISTORY_TIME: Long = TimeUnit.DAYS.toMillis(60)

    @JvmStatic
    fun getOpEdLimit(duration: Long): Long {
        if (duration < TimeUnit.MINUTES.toMillis(15)) return TimeUnit.MINUTES.toMillis(3)
        if (duration < TimeUnit.MINUTES.toMillis(30)) return TimeUnit.MINUTES.toMillis(6)
        return TimeUnit.MINUTES.toMillis(10)
    }
}
