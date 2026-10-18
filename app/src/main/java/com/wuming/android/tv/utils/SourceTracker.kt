package com.wuming.android.tv.utils

/**
 * Deprecated: kept only so existing call sites keep compiling. All writes now go through
 * [DepotManager.trackUsage], which serialises on the same executor as update/validate
 * and writes atomically.
 */
object SourceTracker {
    @JvmStatic
    fun trackSourceUsage(url: String, name: String, type: String, pingTime: Long) {
        DepotManager.trackUsage(url, name, type, pingTime)
    }
}
