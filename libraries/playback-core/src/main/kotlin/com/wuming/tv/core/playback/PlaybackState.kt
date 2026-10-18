package com.wuming.tv.core.playback

/** Complete player state. Host events update one property while preserving the others. */
data class PlaybackState(
    val buffering: Boolean = false,
    val positionSeconds: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val speed: Double = 1.0,
    val paused: Boolean = false,
)
