package com.wuming.tv.core.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistSelectionPolicyTest {

    @Test
    fun `includes matches by url`() {
        val policy = PlaylistSelectionPolicy("flag1", emptyList(), listOf("http://example.com/ep1"))
        assertTrue(policy.includes("flag1", "Episode 1", "http://example.com/ep1"))
    }

    @Test
    fun `includes matches by name ignoring case and whitespace`() {
        val policy = PlaylistSelectionPolicy("flag1", listOf("Episode 1"), emptyList())
        assertTrue(policy.includes("flag1", "  episode  1 ", null))
    }

    @Test
    fun `includes rejects different flag`() {
        val policy = PlaylistSelectionPolicy("flag1", listOf("Episode 1"), emptyList())
        assertFalse(policy.includes("flag2", "Episode 1", null))
    }

    @Test
    fun `size returns episode name count`() {
        val policy = PlaylistSelectionPolicy("flag1", listOf("a", "b"), emptyList())
        assertEquals(2, policy.size())
    }

    @Test
    fun `getFlag returns the flag`() {
        val policy = PlaylistSelectionPolicy("flag1", emptyList(), emptyList())
        assertEquals("flag1", policy.getFlag())
    }
}
