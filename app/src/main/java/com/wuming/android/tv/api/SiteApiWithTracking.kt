package com.wuming.android.tv.api

import com.wuming.android.tv.bean.Result
import com.wuming.android.tv.utils.SitePerformanceTracker

/** Delegates video calls; callers report the final playback outcome to the performance tracker. */
open class SiteApiWithTracking private constructor() {
    companion object {
        @JvmStatic
        @Throws(Exception::class)
        fun playerContent(
            key: String,
            flag: String,
            id: String,
        ): Result = SiteApi.playerContent(key, flag, id)

        @JvmStatic fun recordPlaybackSuccess(
            siteKey: String?,
            speedBytesPerSec: Long,
        ) {
            if (!siteKey.isNullOrEmpty()) SitePerformanceTracker.get().recordSuccess(siteKey, speedBytesPerSec)
        }

        @JvmStatic fun recordPlaybackDeadLink(siteKey: String?) {
            if (!siteKey.isNullOrEmpty()) SitePerformanceTracker.get().recordDeadLink(siteKey)
        }

        @JvmStatic fun recordPlaybackFailure(siteKey: String?) {
            if (!siteKey.isNullOrEmpty()) SitePerformanceTracker.get().recordFailure(siteKey)
        }
    }
}
