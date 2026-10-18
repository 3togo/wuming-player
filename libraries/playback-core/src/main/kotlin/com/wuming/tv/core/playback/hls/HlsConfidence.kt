package com.wuming.tv.core.playback.hls

enum class HlsConfidence {
    NONE,
    LOW,
    MEDIUM,
    HIGH;

    companion object {
        @JvmStatic
        fun min(first: HlsConfidence, second: HlsConfidence): HlsConfidence =
            if (first.ordinal <= second.ordinal) first else second
    }
}
