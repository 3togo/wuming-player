package com.wuming.tv.core.playback

/** Conservative wall-time engagement from consecutive, progressing player observations. */
class ObservedWatchTime(private val maxGapMillis: Long = 5000) {
    private var previous: PlaybackState? = null
    private var lastAt = 0L
    var watchedMillis = 0L
        private set
    fun observe(state: PlaybackState, monotonicMillis: Long) {
        val before = previous
        val elapsed = monotonicMillis - lastAt
        if (before != null && elapsed in 1..maxGapMillis &&
            !before.paused && !state.paused && !before.buffering && !state.buffering &&
            before.speed.isFinite() && before.speed > 0 && state.speed == before.speed) {
            val progress = (state.positionSeconds - before.positionSeconds) * 1000
            // Reject seek jumps, stationary state events and disconnected feedback gaps.
            val expected = elapsed * before.speed
            if (progress.isFinite() && progress > 0 && progress <= expected * 1.5 + 250) {
                watchedMillis += minOf(elapsed, (progress / before.speed).toLong())
            }
        }
        previous = state
        lastAt = monotonicMillis
    }
    fun disconnected() { previous = null }
}
