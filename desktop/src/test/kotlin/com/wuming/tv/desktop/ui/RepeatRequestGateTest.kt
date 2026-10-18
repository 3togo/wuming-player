package com.wuming.tv.desktop.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepeatRequestGateTest {
    @Test
    fun `same request is ignored for one second`() {
        var now = 10_000L
        val gate = RepeatRequestGate(clockMillis = { now })

        assertTrue(gate.accept("play:episode-1"))
        assertFalse(gate.accept("play:episode-1"))
        now += 999
        assertFalse(gate.accept("play:episode-1"))
        now += 1
        assertTrue(gate.accept("play:episode-1"))
    }

    @Test
    fun `different choices remain immediately responsive`() {
        val gate = RepeatRequestGate(clockMillis = { 10_000L })

        assertTrue(gate.accept("category:films"))
        assertTrue(gate.accept("category:series"))
        assertFalse(gate.accept("category:films"))
    }

    @Test
    fun `clock rollback does not admit an accidental repeat`() {
        var now = 10_000L
        val gate = RepeatRequestGate(clockMillis = { now })

        assertTrue(gate.accept("refresh"))
        now = 9_000L
        assertFalse(gate.accept("refresh"))
    }
}
