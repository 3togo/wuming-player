package com.wuming.android.tv.player.exo

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Parcel
import android.util.Base64
import androidx.media3.common.Format
import androidx.media3.common.C
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import com.wuming.android.tv.App
import com.wuming.android.tv.BuildConfig
import com.google.gson.Gson
import com.wuming.tv.core.playback.HardwareCapabilityJson
import com.wuming.tv.core.playback.HardwareCapabilityReport
import com.wuming.tv.core.playback.VideoRenditionPolicy
import java.security.MessageDigest

/** Explicitly saved hardware report, plus legacy format caching before first detection. */
class VideoCapabilityCache @JvmOverloads constructor(
    private val preferences: SharedPreferences,
    private val fingerprint: String,
    private val detector: () -> HardwareCapabilityReport = HardwareCapabilityDetector::detect,
) {
    private var reportLoaded = false
    private var report: HardwareCapabilityReport? = null

    @Synchronized fun savedReport(): HardwareCapabilityReport? {
        if (!reportLoaded) {
            report = HardwareCapabilityJson.decode(preferences.getString("hardware_report_v1", null))
            reportLoaded = true
        }
        return report
    }

    fun savedSupport(format: Format): VideoRenditionPolicy.Support? {
        val saved = savedReport() ?: return null
        val mime = format.sampleMimeType ?: return VideoRenditionPolicy.Support.UNKNOWN
        // These may use a compatible base-layer decoder under another MIME type.
        // A missing direct entry in the report is not proof that playback is impossible.
        if (mime == "video/dolby-vision" || mime == "video/mv-hevc") return VideoRenditionPolicy.Support.UNKNOWN
        val profile = MediaCodecUtil.getCodecProfileAndLevel(format)
        return saved.assess(mime, profile?.first, profile?.second, format.width, format.height,
            format.frameRate, format.bitrate)
    }
    companion object {
        private const val MAX_ENTRIES = 256

        @JvmStatic fun get(): VideoCapabilityCache = Holder.INSTANCE

        private fun key(format: Format): String {
            val parcel = Parcel.obtain()
            try {
                parcel.writeBundle(format.toBundle())
                val hash = MessageDigest.getInstance("SHA-256").digest(parcel.marshall())
                return "format:" + Base64.encodeToString(hash, Base64.NO_WRAP)
            } catch (
                impossible: java.security.NoSuchAlgorithmException,
            ) {
                throw IllegalStateException(impossible)
            } finally {
                parcel.recycle()
            }
        }
    }

    private object Holder {
        val INSTANCE =
            VideoCapabilityCache(
                App.get().getSharedPreferences("video_capabilities", Context.MODE_PRIVATE),
                "1:" + Build.FINGERPRINT + ":" + BuildConfig.VERSION_NAME + ":" + androidx.media3.common.MediaLibraryInfo.VERSION,
            )
    }

    fun interface Query {
        @Throws(MediaCodecUtil.DecoderQueryException::class)
        fun run(): Int
    }

    @Synchronized
    @Throws(MediaCodecUtil.DecoderQueryException::class)
    fun resolve(
        format: Format,
        query: Query,
    ): Int {
        if (format.drmInitData != null) return query.run()
        val saved = savedReport()
        if (saved != null) {
            val support = when (savedSupport(format)) {
                VideoRenditionPolicy.Support.SUPPORTED -> C.FORMAT_HANDLED
                VideoRenditionPolicy.Support.UNSUPPORTED -> C.FORMAT_UNSUPPORTED_SUBTYPE
                else -> C.FORMAT_EXCEEDS_CAPABILITIES
            }
            val codecs = saved.decoders.filter { it.mime == format.sampleMimeType }
            return RendererCapabilities.create(support,
                if (codecs.any { it.adaptive }) RendererCapabilities.ADAPTIVE_SEAMLESS else RendererCapabilities.ADAPTIVE_NOT_SUPPORTED,
                if (codecs.any { it.tunneling }) RendererCapabilities.TUNNELING_SUPPORTED else RendererCapabilities.TUNNELING_NOT_SUPPORTED)
        }
        val key = key(format)
        if (preferences.contains(key)) {
            if (BuildConfig.DEBUG) android.util.Log.d("VideoCapabilities", "Using saved format capability")
            return preferences.getInt(key, 0)
        }
        val support = query.run()
        if (BuildConfig.DEBUG) android.util.Log.d("VideoCapabilities", "Saving format capability=" + support)
        val editor = preferences.edit()
        if (preferences.all.size >=
            MAX_ENTRIES + 3
        ) {
            for (entry in preferences.all.keys) if (entry.startsWith("format:")) editor.remove(entry)
        }
        editor.putInt(key, support).apply()
        return support
    }

    /** Re-query the platform without creating decoders or allocating video buffers. */
    @Synchronized
    @Throws(MediaCodecUtil.DecoderQueryException::class)
    fun refresh(): Int {
        val detected = detector()
        if (!preferences
                .edit()
                .clear()
                .putString(
                    "fingerprint",
                    fingerprint,
                ).putString("hardware_report_v1", Gson().toJson(detected))
                .putLong("updated", detected.detectedAt)
                .commit()
        ) {
            throw IllegalStateException("Unable to save video capabilities")
        }
        report = detected
        reportLoaded = true
        MediaCodecUtil.clearDecoderInfoCache()
        return detected.decoders.size
    }
}
