package com.wuming.tv.core.base

import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

object Formatters {
    @JvmField val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
    @JvmField val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    @JvmField val TIME_SEC: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT).withZone(ZoneId.systemDefault())
    @JvmField val LOCAL_DATETIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.ROOT)
    @JvmField val EPG_DT_SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-ddHH:mm", Locale.ROOT)
    @JvmField val EPG_DT_LONG: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-ddHH:mm:ss", Locale.ROOT)
    @JvmField val EPG_RANGE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)
    @JvmField val EPG_FULL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z", Locale.ROOT)
    @JvmField val EPG_FULL_NO_TZ: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT)
    @JvmField val EPG_FULL_COLON: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss xxx", Locale.ROOT)
}
