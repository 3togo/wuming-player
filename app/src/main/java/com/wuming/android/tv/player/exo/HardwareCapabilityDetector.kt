package com.wuming.android.tv.player.exo

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import com.wuming.android.tv.App
import com.wuming.tv.core.playback.HardwareCapabilityReport
import com.wuming.tv.core.playback.SavedCodecProfile
import com.wuming.tv.core.playback.SavedVideoDecoder
import com.wuming.tv.core.playback.SavedVideoMode

/** Invoked only by the explicit settings action. Queries advertised limits, not a decode benchmark. */
internal object HardwareCapabilityDetector {
    fun detect(): HardwareCapabilityReport {
        val sizes = listOf(320 to 240, 640 to 360, 720 to 480, 720 to 576, 1280 to 720,
            1920 to 1080, 2560 to 1440, 3840 to 2160, 4096 to 2160, 7680 to 4320)
        val decoders = mutableListOf<SavedVideoDecoder>()
        for (codec in MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos) {
            if (codec.isEncoder) continue
            for (mime in codec.supportedTypes.filter { it.startsWith("video/") }) {
                val caps = codec.getCapabilitiesForType(mime)
                // Secure/tunneled-only decoders cannot be used for ordinary video playback.
                if (caps.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback) ||
                    caps.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback)) continue
                val video = caps.videoCapabilities ?: continue
                val modes = sizes.mapNotNull { (width, height) ->
                    if (!video.isSizeSupported(width, height)) null else SavedVideoMode(width, height,
                        video.getSupportedFrameRatesFor(width, height).upper)
                }
                decoders.add(SavedVideoDecoder(codec.name, mime,
                    caps.profileLevels.map { SavedCodecProfile(it.profile, it.level) }, modes,
                    video.bitrateRange.upper,
                    caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback),
                    caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback),
                    Build.VERSION.SDK_INT >= 29 && codec.isHardwareAccelerated,
                    video.supportedWidths.lower, video.supportedWidths.upper,
                    video.supportedHeights.lower, video.supportedHeights.upper,
                    video.widthAlignment, video.heightAlignment))
            }
        }
        check(decoders.isNotEmpty()) { "No video decoder capability data" }
        return HardwareCapabilityReport(System.currentTimeMillis(), Build.FINGERPRINT, decoders,
            HdrDisplayChecker.querySupportedHdrTypes(App.get()))
    }
}
