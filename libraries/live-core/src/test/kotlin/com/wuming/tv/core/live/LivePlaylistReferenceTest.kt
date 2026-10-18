package com.wuming.tv.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LivePlaylistReferenceTest {

    @Test
    fun `retains provider-neutral playlist identity`() {
        val reference = LivePlaylistReference("Family TV", "https://example.test/live.m3u")

        assertEquals("Family TV", reference.name)
        assertEquals("https://example.test/live.m3u", reference.location)
    }

    @Test
    fun `rejects unusable references`() {
        assertThrows(IllegalArgumentException::class.java) {
            LivePlaylistReference("", "https://example.test/live.m3u")
        }
        assertThrows(IllegalArgumentException::class.java) {
            LivePlaylistReference("Live", "")
        }
    }
}
