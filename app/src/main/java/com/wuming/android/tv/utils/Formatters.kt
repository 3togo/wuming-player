package com.wuming.android.tv.utils

import java.time.format.DateTimeFormatter

/** Stable fields forwarding to the shared formatter definitions. */
object Formatters {
    @JvmField val DATE: DateTimeFormatter = com.wuming.tv.core.base.Formatters.DATE
    @JvmField val TIME: DateTimeFormatter = com.wuming.tv.core.base.Formatters.TIME
    @JvmField val TIME_SEC: DateTimeFormatter = com.wuming.tv.core.base.Formatters.TIME_SEC
    @JvmField val LOCAL_DATETIME: DateTimeFormatter = com.wuming.tv.core.base.Formatters.LOCAL_DATETIME
    @JvmField val EPG_DT_SHORT: DateTimeFormatter = com.wuming.tv.core.base.Formatters.EPG_DT_SHORT
    @JvmField val EPG_DT_LONG: DateTimeFormatter = com.wuming.tv.core.base.Formatters.EPG_DT_LONG
    @JvmField val EPG_RANGE: DateTimeFormatter = com.wuming.tv.core.base.Formatters.EPG_RANGE
    @JvmField val EPG_FULL: DateTimeFormatter = com.wuming.tv.core.base.Formatters.EPG_FULL
    @JvmField val EPG_FULL_NO_TZ: DateTimeFormatter = com.wuming.tv.core.base.Formatters.EPG_FULL_NO_TZ
    @JvmField val EPG_FULL_COLON: DateTimeFormatter = com.wuming.tv.core.base.Formatters.EPG_FULL_COLON
}
