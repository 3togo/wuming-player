package com.wuming.tv.core.playback

import org.junit.Assert.*
import org.junit.Test

class PlaybackDiagnosticsTest {
    private fun s(t: Long, p: Long, f: Long, state: Int = 3, buffer: Long = 4000,
                  generation: Int = 1, playing: Boolean = state == 3) =
        PlaybackDiagnostics.Sample(t, p, buffer, f, state, playing, generation)
    @Test fun firstFrameDuringStartupIsNotARebufferOrPlaybackStall() {
        val d = PlaybackDiagnostics(0)
        d.observe(s(1000, 0, 1, 2, 100))
        d.observe(s(2000, 0, 1, 2, 200))
        d.observe(s(3000, 0, 1, 2, 300))
        assertEquals(1000, d.startupMillis)
        assertEquals(0, d.rebufferCount)
        assertEquals(0, d.rebufferMillis)
        assertEquals(0, d.maxStallMillis)
        d.observe(s(4000, 1000, 25))
        d.observe(s(5000, 1000, 25, 2, 0))
        d.observe(s(6000, 1000, 25, 2, 0))
        assertEquals(1, d.rebufferCount)
        assertEquals(1000, d.rebufferMillis)
        d.observe(s(7000, 0, 1, 2, 100, 2))
        d.observe(s(8000, 0, 1, 2, 200, 2))
        assertEquals(1, d.rebufferCount)
        assertEquals(1000, d.rebufferMillis)
    }
    @Test fun transportStallAndPlayerResetRemainDistinct() {
        val d = PlaybackDiagnostics(0)
        d.observe(s(1000, 1000, 25))
        d.observe(s(2000, 2000, 50))
        d.observe(s(3000, 2000, 50, 2, 0))
        d.observe(s(4000, 2000, 50, 2, 0))
        assertEquals("transport-starved", d.diagnosis)
        assertEquals(1, d.rebufferCount)
        assertEquals(1000, d.rebufferMillis)
        assertEquals(2000, d.maxStallMillis)
        d.observe(s(5000, 0, 0, 1, 0, 2, false))
        assertEquals("player-reset", d.diagnosis)
        assertEquals(1, d.resetCount)
        assertEquals(1000, d.progressMillis)
    }
    @Test fun partialFrameAtBufferEdgeStillIdentifiesTransportStarvation() {
        val d = PlaybackDiagnostics(0)
        d.observe(s(0, 0, 1))
        d.observe(s(1000, 871, 24, 2, 49))
        d.observe(s(2000, 871, 24, 2, 49))
        assertEquals("transport-starved", d.diagnosis)
        assertEquals(1, d.rebufferCount)
        assertEquals(1000, d.maxStallMillis)
    }
    @Test fun liveWindowSlidesAndSeeksCannotInventWatchedTime() {
        val d = PlaybackDiagnostics(0)
        d.observe(s(1000, 40000, 25))
        d.observe(s(2000, 41000, 50))
        d.observe(s(3000, 22000, 75)) // same decoder, shifted live window
        d.observe(s(4000, 23000, 100))
        d.observe(s(5000, 200000, 125)) // seek
        assertEquals(2000, d.progressMillis)
        assertEquals(0, d.resetCount)
        assertEquals(100, d.renderedFrames)
        assertEquals(0, d.maxStallMillis)
    }
    @Test fun bufferedVideoWithoutFramesIsNotTransportStarvation() {
        val d = PlaybackDiagnostics(0)
        d.observe(s(0, 0, 25))
        d.observe(s(1000, 0, 25))
        d.observe(s(2000, 0, 25))
        assertEquals("decoder-or-render-stalled", d.diagnosis)
        d.observe(s(3000, 0, 25, playing = false))
        assertEquals("not-playing", d.diagnosis)
        assertEquals(0, d.progressMillis)
    }
}
