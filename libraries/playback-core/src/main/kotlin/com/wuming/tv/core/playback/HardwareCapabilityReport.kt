package com.wuming.tv.core.playback

/** Serializable evidence from an explicit platform detection run; never refreshes itself. */
data class HardwareCapabilityReport(
    val detectedAt: Long,
    val device: String,
    val decoders: List<SavedVideoDecoder>,
    val hdrTypes: Set<Int>,
) {
    fun assess(mime: String, profile: Int?, level: Int?, width: Int, height: Int,
               frameRate: Float, bitrate: Int): VideoRenditionPolicy.Support {
        val candidates = decoders.filter { it.mime == mime }
        if (candidates.isEmpty()) return VideoRenditionPolicy.Support.UNSUPPORTED
        val matching = if (profile == null || level == null) candidates else candidates.filter { codec ->
            codec.profiles.any { it.profile == profile }
        }
        if (matching.isEmpty() && candidates.all { it.profiles.isEmpty() }) return VideoRenditionPolicy.Support.UNKNOWN
        if (matching.isEmpty()) return VideoRenditionPolicy.Support.UNSUPPORTED
        if (profile == null || width <= 0 || height <= 0) return VideoRenditionPolicy.Support.UNKNOWN
        if (level != null && matching.none { codec -> codec.profiles.any { it.profile == profile && it.level >= level } }) {
            return VideoRenditionPolicy.Support.EXCEEDS
        }
        if (matching.none { codec -> codec.modes.any { it.width == width && it.height == height } }) {
            return VideoRenditionPolicy.Support.UNKNOWN
        }
        if (matching.any { codec ->
                (bitrate <= 0 || bitrate <= codec.maxBitrate) && codec.modes.any { mode ->
                    mode.width == width && mode.height == height && (frameRate <= 0 || frameRate <= mode.maxFrameRate)
                }
            }) return VideoRenditionPolicy.Support.SUPPORTED
        // A missing size/rate probe is advisory, not proof that decoding is impossible.
        return VideoRenditionPolicy.Support.EXCEEDS
    }
}

data class SavedVideoDecoder(
    val name: String,
    val mime: String,
    val profiles: List<SavedCodecProfile>,
    val modes: List<SavedVideoMode>,
    val maxBitrate: Int,
    val adaptive: Boolean,
    val tunneling: Boolean,
    val hardwareAccelerated: Boolean,
    val minWidth: Int = 0,
    val maxWidth: Int = 0,
    val minHeight: Int = 0,
    val maxHeight: Int = 0,
    val widthAlignment: Int = 1,
    val heightAlignment: Int = 1,
)

data class SavedCodecProfile(val profile: Int, val level: Int)
data class SavedVideoMode(val width: Int, val height: Int, val maxFrameRate: Double)
