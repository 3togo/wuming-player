package com.wuming.tv.core.playback

/** One-shot per-selection transitions, independent of Android callbacks or player engines. */
class PlaybackSessionProgress {
    data class Tick(val skipEnding: Boolean, val prewarmNext: Boolean)
    private var endingTriggered = false
    private var nextPrewarmed = false

    fun reset() { endingTriggered = false; nextPrewarmed = false }

    @JvmOverloads
    fun observe(position: Long, duration: Long, ending: Long, playing: Boolean = true): Tick {
        if (duration <= 0 || position < 0) return Tick(false, false)
        val skip = !endingTriggered && ending > 0 && position >= duration - ending.coerceAtMost(duration)
        if (skip) endingTriggered = true
        // Ending may synchronously select another episode. Never prewarm from that stale tick.
        // A paused or completed timeline must not spend the one-shot preparation attempt.
        val prewarm = playing && !skip && !endingTriggered && !nextPrewarmed && position > 0 && position < duration &&
            position >= duration - duration / 5
        if (prewarm) nextPrewarmed = true
        return Tick(skip, prewarm)
    }
}
