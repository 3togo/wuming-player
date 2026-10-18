package com.wuming.android.tv.player.exo

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.wuming.android.tv.R
import com.wuming.android.tv.utils.ResUtil
import java.io.IOException
import java.util.Locale

/** Keeps the rejected stream metadata available even before track selection completes. */
internal class UnsupportedVideoException(
    private val format: Format,
    private val support: Int,
    val reason: Reason = Reason.DECODER,
) : IOException("Unsupported video: " + Format.toLogString(format) + "; support=" + support) {
    enum class Reason { DECODER, HDR_DISPLAY, NO_COMPATIBLE_RENDITION }

    companion object {
        @JvmStatic fun find(error: Throwable): UnsupportedVideoException? {
            var cause: Throwable? = error
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            while (cause != null && seen.add(cause)) {
                if (cause is UnsupportedVideoException) return cause
                cause = cause.cause
            }
            return null
        }

        @JvmStatic fun isCapabilityRejection(error: Throwable): Boolean = find(error) != null
    }

    fun messageResource(): Int = when (reason) {
        Reason.HDR_DISPLAY -> R.string.error_play_hdr_display
        Reason.NO_COMPATIBLE_RENDITION -> R.string.error_play_no_compatible_quality
        Reason.DECODER -> R.string.error_play_video_unsupported
    }

    fun details(): String {
        val unknown = ResUtil.getString(R.string.video_info_unknown)
        val codec = if (format.sampleMimeType == null) unknown else when (format.sampleMimeType) {
            MimeTypes.VIDEO_H265 -> "HEVC (H.265)"
            MimeTypes.VIDEO_H264 -> "AVC (H.264)"
            MimeTypes.VIDEO_AV1 -> "AV1"
            MimeTypes.VIDEO_VP9 -> "VP9"
            else -> format.sampleMimeType
        }
        val resolution = if (format.width > 0 && format.height > 0)
            "${format.width} × ${format.height}" else unknown
        val rate = if (format.frameRate > 0)
            String.format(Locale.ROOT, "%.2f fps", format.frameRate) else unknown
        var result = ResUtil.getString(R.string.video_info_format, codec, resolution, rate)
        if (format.codecs != null) result += "\n" + format.codecs
        val colorInfo = format.colorInfo
        if (colorInfo != null && (colorInfo.colorSpace != Format.NO_VALUE
                || colorInfo.colorTransfer != Format.NO_VALUE
                || colorInfo.lumaBitdepth != Format.NO_VALUE)) {
            result += "\n" + colorInfo.toLogString()
        }
        return result + "\n" + ResUtil.getString(
            if (support == C.FORMAT_EXCEEDS_CAPABILITIES) R.string.video_info_exceeds
            else R.string.video_info_unsupported
        )
    }
}
