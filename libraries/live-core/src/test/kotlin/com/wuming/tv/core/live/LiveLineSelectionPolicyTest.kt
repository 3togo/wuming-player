package com.wuming.tv.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveLineSelectionPolicyTest {

    private val lines = listOf("https://a.test/live.m3u8", "https://b.test/live.m3u8", "https://c.test/live.m3u8")

    private fun measured(vararg pairs: Pair<String, LiveLineMeasurement>) = mapOf(*pairs)

    @Test
    fun keepsTheRequestedLineWhileNothingHasBeenProbed() {
        assertEquals(1, LiveLineSelectionPolicy.select(lines, 1, emptyMap()))
    }

    @Test
    fun keepsARequestedLineThatAnswered() {
        val results = measured(
            lines[1] to LiveLineMeasurement(reachable = true, latencyMillis = 900),
            lines[0] to LiveLineMeasurement(reachable = true, latencyMillis = 40),
        )
        assertEquals(1, LiveLineSelectionPolicy.select(lines, 1, results))
    }

    @Test
    fun replacesADeadLineWithTheQuicktestAnsweringOne() {
        val results = measured(
            lines[0] to LiveLineMeasurement(reachable = true, latencyMillis = 800),
            lines[2] to LiveLineMeasurement(reachable = true, latencyMillis = 120),
            lines[1] to LiveLineMeasurement(reachable = false),
        )
        assertEquals(2, LiveLineSelectionPolicy.select(lines, 1, results))
    }

    @Test
    fun keepsADeadLineWhenNoAlternativeHasAnswered() {
        val results = measured(
            lines[1] to LiveLineMeasurement(reachable = false),
            lines[2] to LiveLineMeasurement(reachable = false),
        )
        assertEquals(1, LiveLineSelectionPolicy.select(lines, 1, results))
    }

    @Test
    fun clampsAnIndexFromAStalePlaylistIntoRange() {
        assertEquals(2, LiveLineSelectionPolicy.select(lines, 9, emptyMap()))
        assertEquals(0, LiveLineSelectionPolicy.select(lines, -3, emptyMap()))
        assertEquals(0, LiveLineSelectionPolicy.select(emptyList(), 3, emptyMap()))
    }

    @Test
    fun ignoresUnreachableLinesWhenRanking() {
        val results = measured(
            lines[0] to LiveLineMeasurement(reachable = false),
            lines[1] to LiveLineMeasurement(reachable = true, latencyMillis = 5000),
            lines[2] to LiveLineMeasurement(reachable = true, latencyMillis = 50),
        )
        assertEquals(lines[2], LiveLineSelectionPolicy.fastest(lines, results))
        assertNull(LiveLineSelectionPolicy.fastest(lines, measured(lines[0] to LiveLineMeasurement(false))))
    }

    @Test
    fun probesUnknownLinesAndSkipsAnsweringOnes() {
        assertTrue(LiveLineSelectionPolicy.shouldProbe(null, nowMillis = 0, measuredAtMillis = 0))
        assertFalse(LiveLineSelectionPolicy.shouldProbe(
            LiveLineMeasurement(true, 10), nowMillis = 0, measuredAtMillis = 0))
    }

    @Test
    fun rechecksADeadLineOnlyAfterTheCooldown() {
        val dead = LiveLineMeasurement(reachable = false)
        assertFalse(LiveLineSelectionPolicy.shouldProbe(dead, nowMillis = 0, measuredAtMillis = 0))
        assertTrue(LiveLineSelectionPolicy.shouldProbe(
            dead,
            nowMillis = LiveLineSelectionPolicy.REPROBE_AFTER_MILLIS,
            measuredAtMillis = 0,
        ))
    }
}
