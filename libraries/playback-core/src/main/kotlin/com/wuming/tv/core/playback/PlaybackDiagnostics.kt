package com.wuming.tv.core.playback

/** Bounded monotonic observations; positions may jump when a live window slides. */
class PlaybackDiagnostics(private val startedAt: Long) {
    data class Sample(val at: Long, val position: Long, val buffered: Long, val frames: Long,
                      val state: Int, val playing: Boolean, val generation: Int)
    private var previous: Sample? = null
    private var stallMillis = 0L
    private var hasPlayed = false
    var startupMillis: Long = -1; private set
    var progressMillis: Long = 0; private set
    var renderedFrames: Long = 0; private set
    var rebufferCount: Int = 0; private set
    var rebufferMillis: Long = 0; private set
    var resetCount: Int = 0; private set
    var maxStallMillis: Long = 0; private set
    var diagnosis: String = "starting"; private set

    @Synchronized fun observe(sample: Sample) {
        val before = previous
        if (startupMillis < 0 && sample.frames > 0) startupMillis = sample.at - startedAt
        val delta = if (before == null) 0 else sample.at - before.at
        val reset = before != null && sample.generation != before.generation
        if (reset) {
            resetCount++
            hasPlayed = false
        }
        if (before != null && delta in 1..5000 && !reset) {
            val frameDelta = (sample.frames - before.frames).coerceAtLeast(0)
            renderedFrames += frameDelta
            val positionDelta = sample.position - before.position
            if (sample.playing && before.playing && frameDelta > 0 && positionDelta in 1..(delta + 2000)) {
                progressMillis += minOf(delta, positionDelta)
            }
            if (hasPlayed && sample.state == 2) {
                if (before.state != 2) rebufferCount++
                if (before.state == 2) rebufferMillis += delta
            }
            if (hasPlayed && frameDelta == 0L && (sample.playing || sample.state == 2)) {
                stallMillis += delta
                maxStallMillis = maxOf(maxStallMillis, stallMillis)
            } else stallMillis = 0
        } else stallMillis = 0
        diagnosis = when {
            reset -> "player-reset"
            // ExoPlayer can retain a partial frame at the buffered edge while waiting for transport.
            sample.state == 2 && sample.buffered <= 250L -> "transport-starved"
            stallMillis > 1000 && sample.buffered > 0 && sample.playing -> "decoder-or-render-stalled"
            sample.playing -> "playing"
            else -> "not-playing"
        }
        if (sample.playing && sample.frames > 0) hasPlayed = true
        previous = sample
    }
}
