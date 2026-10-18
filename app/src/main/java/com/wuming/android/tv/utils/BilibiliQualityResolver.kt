package com.wuming.android.tv.utils

import com.github.catvod.net.OkHttp
import com.github.catvod.net.PersistentCookieJar
import com.wuming.android.tv.bean.Result
import com.wuming.tv.core.playback.BilibiliPlaybackQualities

/** Android account/transport port; the provider continues to own MPD generation. */
object BilibiliQualityResolver {
    @JvmStatic fun refresh(result: Result) {
        val source = result.url.getValues().firstNotNullOfOrNull {
            BilibiliPlaybackQualities.source(it.getV())
        } ?: return
        if (!PersistentCookieJar.get().hasLoginCookies("https://www.bilibili.com/")) return
        try {
            // REGRESSION (2026-09-28): the provider offered only 480p/360p despite a valid
            // saved session; the same account's API response and provider proxy supported 720p.
            // Use this shared-cookie client on every resolution. A fresh anonymous client or
            // provider-cached login flag brings back the low-resolution preview AND cast menu.
            // Credentials belong on the Bilibili API request, never in TV URLs/CDN headers.
            // See docs/optimization/bilibili-authenticated-quality.md and
            // BilibiliPlaybackQualitiesTest for the quality-list contract.
            OkHttp.newCall(source.apiUrl(), mapOf(
                "User-Agent" to "Mozilla/5.0", "Referer" to "https://www.bilibili.com/"
            )).execute().use { response ->
                check(response.isSuccessful)
                val qualities = BilibiliPlaybackQualities.parse(source, response.body.string())
                if (qualities.isEmpty()) return
                val urls = result.url
                urls.getValues().clear()
                for (quality in qualities) urls.add(quality.label, quality.url)
                urls.set(0)
                AppLog.d("BilibiliQuality", "Authenticated qualities=" + qualities.joinToString(",") { it.id.toString() })
            }
        } catch (error: Exception) {
            // Preserve playable provider results on API/network failure; never log cookies or signed URLs.
            AppLog.w("BilibiliQuality", "Quality refresh failed: " + error.javaClass.simpleName)
        }
    }
}
