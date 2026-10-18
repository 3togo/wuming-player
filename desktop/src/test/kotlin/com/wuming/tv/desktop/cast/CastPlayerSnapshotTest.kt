package com.wuming.tv.desktop.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CastPlayerSnapshotTest {

    @Test
    fun `data class properties are accessible without parentheses`() {
        val snapshot = CastPlayer.Snapshot("PLAYING", 10.0, 120.0, 50, false)
        assertEquals("PLAYING", snapshot.state)
        assertEquals(10.0, snapshot.position, 0.001)
        assertEquals(120.0, snapshot.duration, 0.001)
        assertEquals(50, snapshot.volume)
        assertFalse(snapshot.muted)
    }

    @Test
    fun `equals compares all fields`() {
        val a = CastPlayer.Snapshot("PLAYING", 10.0, 120.0, 50, false)
        val b = CastPlayer.Snapshot("PLAYING", 10.0, 120.0, 50, false)
        val c = CastPlayer.Snapshot("PAUSED", 10.0, 120.0, 50, false)
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun `hashCode is consistent with equals`() {
        val a = CastPlayer.Snapshot("PLAYING", 10.0, 120.0, 50, false)
        val b = CastPlayer.Snapshot("PLAYING", 10.0, 120.0, 50, false)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `copy creates modified instance`() {
        val original = CastPlayer.Snapshot("PLAYING", 10.0, 120.0, 50, false)
        val muted = original.copy(muted = true)
        assertEquals("PLAYING", muted.state)
        assertEquals(10.0, muted.position, 0.001)
        assertEquals(120.0, muted.duration, 0.001)
        assertEquals(50, muted.volume)
        assertTrue(muted.muted)
        assertFalse(original.muted)
    }

    @Test
    fun `toString contains all fields`() {
        val snapshot = CastPlayer.Snapshot("STOPPED", 0.0, 0.0, 0, true)
        val str = snapshot.toString()
        assertTrue(str.contains("STOPPED"))
        assertTrue(str.contains("muted=true"))
    }
}
