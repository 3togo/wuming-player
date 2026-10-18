package com.wuming.android.tv.api.projection

import com.github.catvod.net.OkHttp
import com.wuming.android.tv.R
import com.wuming.android.tv.bean.CastVideo
import com.wuming.android.tv.server.CastMediaAccess
import com.wuming.android.tv.server.Server
import com.wuming.android.tv.utils.AppLog
import com.wuming.android.tv.utils.HttpResponseText
import com.wuming.android.tv.utils.Notify
import com.wuming.android.tv.utils.ResUtil
import com.wuming.tv.core.playback.BilibiliCastPlayback
import java.util.concurrent.TimeUnit

/** Account and HTTP ports. CDN credentials never travel in renderer metadata or URLs. */
object BilibiliCastResolver {
    @JvmStatic fun resolve(video: CastVideo): BilibiliCastPlayback.Stream? = try {
        BilibiliCastPlayback.resolve(video.url() ?: "", Server.get().getAddress()) { url, headers ->
            val call = OkHttp.newCall(url, headers)
            call.timeout().timeout(8, TimeUnit.SECONDS)
            call.execute().use { response ->
                check(response.isSuccessful)
                HttpResponseText.read(response, 1_000_000)
            }
        }
    } catch (error: Exception) {
        AppLog.w("BilibiliCast", "Progressive resolution unavailable: ${error.javaClass.simpleName}")
        null
    }

    /** Apply only after the caller has checked its cast generation. */
    @JvmStatic fun prepare(video: CastVideo, stream: BilibiliCastPlayback.Stream): CastVideo {
        val relay = CastMediaAccess.relay(stream.url, BilibiliCastPlayback.headers)
        AppLog.i("BilibiliCast", "Using progressive MP4 quality=${stream.quality}")
        Notify.show(ResUtil.getString(R.string.cast_compatible_quality, stream.label))
        return CastVideo(video.name(), relay, video.position(), video.duration(), BilibiliCastPlayback.headers,
            video.nextName(), video.nextUrl(), video.siteKey(), video.flag(), video.audioTrackFormat())
    }
}
