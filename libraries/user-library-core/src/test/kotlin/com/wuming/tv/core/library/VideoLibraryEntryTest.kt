package com.wuming.tv.core.library

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoLibraryEntryTest {

    @Test
    fun `position and duration default to zero`() {
        val entry = VideoLibraryEntry(sourceKey = "site", itemId = "vod")
        assertEquals(0L, entry.positionMillis)
        assertEquals(0L, entry.durationMillis)
    }

    @Test
    fun `position and duration are preserved in copy`() {
        val entry = VideoLibraryEntry(
            sourceKey = "site",
            itemId = "vod",
            positionMillis = 45_000L,
            durationMillis = 1_800_000L,
        )
        val copied = entry.copy(itemId = "vod-2")
        assertEquals(45_000L, copied.positionMillis)
        assertEquals(1_800_000L, copied.durationMillis)
    }

    @Test
    fun `key is unaffected by position fields`() {
        val a = VideoLibraryEntry(sourceKey = "site", itemId = "vod", positionMillis = 100L)
        val b = VideoLibraryEntry(sourceKey = "site", itemId = "vod", positionMillis = 200L)
        assertEquals(a.key, b.key)
    }
}
