package com.wuming.tv.core.playback

import org.junit.Assert.*
import org.junit.Test

class PlaybackSessionProgressTest {
    @Test fun prewarmAndEndingRunOncePerSelection() {
        val session = PlaybackSessionProgress()
        assertFalse(session.observe(79, 100, 5).prewarmNext)
        assertTrue(session.observe(80, 100, 5).prewarmNext)
        assertFalse(session.observe(81, 100, 5).prewarmNext)
        assertTrue(session.observe(95, 100, 5).skipEnding)
        assertFalse(session.observe(96, 100, 5).skipEnding)
        session.reset()
        assertTrue(session.observe(80, 100, 5).prewarmNext)
    }
    @Test fun endingTickCannotPrewarmASelectionChangedByItsCallback() {
        val session = PlaybackSessionProgress()
        val tick = session.observe(99, 100, 5)
        assertTrue(tick.skipEnding)
        assertFalse(tick.prewarmNext)
        assertFalse(session.observe(99, 100, 5).prewarmNext)
    }
    @Test fun pausedOrBufferingPlaybackDoesNotSpendThePrewarmAttempt() {
        val session = PlaybackSessionProgress()
        assertFalse(session.observe(80, 100, 0, false).prewarmNext)
        assertFalse(session.observe(90, 100, 0, false).prewarmNext)
        assertTrue(session.observe(90, 100, 0, true).prewarmNext)
        assertFalse(session.observe(91, 100, 0, true).prewarmNext)
    }
    @Test fun completedOrOutOfRangePositionsCannotStartSpeculation() {
        val session = PlaybackSessionProgress()
        assertFalse(session.observe(100, 100, 0).prewarmNext)
        assertFalse(session.observe(101, 100, 0).prewarmNext)
        assertTrue(session.observe(90, 100, 0).prewarmNext)
    }
    @Test fun pausingDoesNotSuppressExistingEndingSkipBehavior() {
        val session = PlaybackSessionProgress()
        assertTrue(session.observe(95, 100, 5, false).skipEnding)
        assertFalse(session.observe(95, 100, 5, true).skipEnding)
    }
    @Test fun unknownDurationsAndLargePositionsDoNotOverflow() {
        val session = PlaybackSessionProgress()
        assertFalse(session.observe(1, -1, 5).skipEnding)
        assertFalse(session.observe(-1, 100, 5).prewarmNext)
        assertTrue(session.observe(Long.MAX_VALUE - 1, Long.MAX_VALUE, 0).prewarmNext)
    }
}
