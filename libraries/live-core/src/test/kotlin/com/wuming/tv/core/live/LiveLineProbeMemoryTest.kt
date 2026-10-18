package com.wuming.tv.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveLineProbeMemoryTest {

    @Test
    fun remembersAndReadsBackMeasurement() {
        val memory = LiveLineProbeMemory(clock = { 1_000L })
        memory.remember("https://a", LiveLineMeasurement(true, 42))
        assertEquals(LiveLineMeasurement(true, 42), memory.sample("https://a")?.measurement)
        assertTrue(memory.allMeasurements()["https://a"]?.reachable ?: false)
    }

    @Test
    fun reachableLineIsNotReprobed() {
        val memory = LiveLineProbeMemory(clock = { 1_000L })
        memory.remember("https://a", LiveLineMeasurement(true, 10))
        assertFalse(memory.shouldReprobe("https://a"))
    }

    @Test
    fun unknownLineShouldBeProbed() {
        val memory = LiveLineProbeMemory(clock = { 1_000L })
        assertTrue(memory.shouldReprobe("https://never"))
    }

    @Test
    fun deadLineReprobesAfterWindow() {
        val clock = mutableListOf(1_000L)
        val memory = LiveLineProbeMemory(clock = { clock.first() })
        memory.remember("https://dead", LiveLineMeasurement(false))
        assertFalse(memory.shouldReprobe("https://dead")) // fresh dead line is left alone
        clock[0] = 1_000L + LiveLineSelectionPolicy.REPROBE_AFTER_MILLIS + 1
        assertTrue(memory.shouldReprobe("https://dead")) // past the window
    }

    @Test
    fun measuredLinesDropsLinesPastTheirWindow() {
        val clock = mutableListOf(1_000L)
        val memory = LiveLineProbeMemory(clock = { clock.first() })
        memory.remember("https://dead", LiveLineMeasurement(false))
        // Fresh dead line is still "remembered" (not past window), so it is included.
        assertTrue(memory.measuredLines().containsKey("https://dead"))
        clock[0] = 1_000L + LiveLineSelectionPolicy.REPROBE_AFTER_MILLIS + 1
        // Past the window it is dropped and reads as unknown.
        assertFalse(memory.measuredLines().containsKey("https://dead"))
    }

    @Test
    fun expiredFailureNoLongerOverridesRequestedRoute() {
        var now = 0L
        val memory = LiveLineProbeMemory(clock = { now })
        val lines = listOf("https://recovered", "https://alternate")
        memory.remember(lines[0], LiveLineMeasurement(false))
        memory.remember(lines[1], LiveLineMeasurement(true, 10))
        assertEquals(1, LiveLineSelectionPolicy.select(lines, 0, memory.measuredLines()))
        now = LiveLineSelectionPolicy.REPROBE_AFTER_MILLIS
        assertEquals(0, LiveLineSelectionPolicy.select(lines, 0, memory.measuredLines()))
    }

    @Test
    fun evictsLeastRecentlyUsedBeyondCapacity() {
        val memory = LiveLineProbeMemory(maxRemembered = 3, clock = { 1_000L })
        repeat(5) { memory.remember("https://$it", LiveLineMeasurement(true)) }
        assertEquals(3, memory.allMeasurements().size)
        assertNull(memory.sample("https://0"))
        assertEquals(LiveLineMeasurement(true), memory.sample("https://4")?.measurement)
    }
}
