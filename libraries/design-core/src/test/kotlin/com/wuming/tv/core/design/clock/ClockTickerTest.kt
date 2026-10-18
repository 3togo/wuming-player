package com.wuming.tv.core.design.clock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ClockTickerTest {

    private lateinit var ticker: ClockTicker

    @Before
    fun setUp() {
        ticker = ClockTicker.create()
    }

    @Test
    fun create_returnsNewInstance() {
        assertNotNull(ClockTicker.create())
    }

    @Test
    fun isRunning_defaultsToFalse() {
        assertFalse(ticker.isRunning())
    }

    @Test
    fun start_setsRunningTrue() {
        ticker.start()
        assertTrue(ticker.isRunning())
    }

    @Test
    fun stop_setsRunningFalse() {
        ticker.start()
        ticker.stop()
        assertFalse(ticker.isRunning())
    }

    @Test
    fun start_idempotent() {
        ticker.start()
        ticker.start()
        assertTrue(ticker.isRunning())
    }

    @Test
    fun format_setsPattern() {
        val result = ticker.format("HH:mm:ss")
        assertTrue(result === ticker)
    }

    @Test
    fun tick_dispatchesToListener() {
        val times = mutableListOf<Long>()
        val formatted = mutableListOf<String?>()
        ticker.addListener { timeMillis, formattedTime ->
            times.add(timeMillis)
            formatted.add(formattedTime)
        }
        ticker.format("HH:mm:ss")
        ticker.start()
        ticker.tick()
        assertEquals(1, times.size)
        assertTrue(times[0] > 0)
        assertNotNull(formatted[0])
        assertFalse(formatted[0]!!.isEmpty())
    }

    @Test
    fun tick_doesNothingWhenStopped() {
        val times = mutableListOf<Long>()
        ticker.addListener { timeMillis, _ -> times.add(timeMillis) }
        ticker.tick()
        assertTrue(times.isEmpty())
    }

    @Test
    fun tick_withoutFormat_returnsNullFormatted() {
        val formatted = mutableListOf<String?>()
        ticker.addListener { _, formattedTime -> formatted.add(formattedTime) }
        ticker.start()
        ticker.tick()
        assertEquals(1, formatted.size)
        assertNull(formatted[0])
    }

    @Test
    fun addListener_idempotent() {
        val count = intArrayOf(0)
        val listener = ClockTicker.TickListener { _, _ -> count[0]++ }
        ticker.addListener(listener)
        ticker.addListener(listener)
        ticker.start()
        ticker.tick()
        assertEquals(1, count[0])
    }

    @Test
    fun removeListener_stopsReceivingTicks() {
        val count = intArrayOf(0)
        val listener = ClockTicker.TickListener { _, _ -> count[0]++ }
        ticker.addListener(listener)
        ticker.start()
        ticker.tick()
        assertEquals(1, count[0])
        ticker.removeListener(listener)
        ticker.tick()
        assertEquals(1, count[0])
    }

    @Test
    fun addNullListener_doesNotThrow() {
        ticker.addListener(null)
        ticker.start()
        ticker.tick()
    }
}
