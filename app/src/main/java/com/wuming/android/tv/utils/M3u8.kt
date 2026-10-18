package com.wuming.android.tv.utils

import com.wuming.android.tv.api.config.RuleConfig
import com.wuming.tv.core.playback.HlsPlaylistPurifier
import com.wuming.tv.core.playback.HlsAdRule
import com.wuming.tv.core.playback.HlsAdFilterEngine
import com.wuming.tv.core.playback.HlsResolutionAdFilter

/** Android rule-configuration adapter for the platform-neutral HLS purifier. */
object M3u8 {
    @JvmStatic
    fun isAd(regex: String): Boolean = HlsPlaylistPurifier.isAd(regex)

    @JvmStatic
    fun purify(segmentBaseUrl: String, playlist: String): String? =
        purify(segmentBaseUrl, segmentBaseUrl, playlist)

    @JvmStatic
    fun purify(segmentBaseUrl: String, ruleBaseUrl: String, playlist: String): String? =
        HlsAdFilterEngine.purify(segmentBaseUrl, ruleBaseUrl, playlist, coreRules(), null, UrlUtil::resolve).replacement

    @JvmStatic
    fun purify(segmentBaseUrl: String, ruleBaseUrl: String, playlist: String,
               probe: HlsResolutionAdFilter.Probe): String? {
        val prepared = HlsAdFilterEngine.purify(segmentBaseUrl, ruleBaseUrl, playlist,
            coreRules(), probe, UrlUtil::resolve)
        if (prepared.legacyFailure != null) android.util.Log.w("HlsAdFilter",
            "Configured playlist rules failed: ${prepared.legacyFailure}")
        if (prepared.resolution.removedSegments > 0) android.util.Log.i("HlsAdFilter",
            "resolution-reset: removedBlocks=${prepared.resolution.removedBlocks}, removedSegments=${prepared.resolution.removedSegments}")
        if (prepared.insertion.removedSegments > 0) android.util.Log.i("HlsAdFilter",
            "timestamp-insertion: removedBlocks=${prepared.insertion.removedBlocks}, removedSegments=${prepared.insertion.removedSegments}")
        return prepared.replacement
    }

    private fun coreRules(): List<HlsAdRule> = RuleConfig.get().rules.map {
        HlsAdRule(hosts = it.getHosts(), patterns = it.getRegex())
    }
}
