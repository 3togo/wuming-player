package com.wuming.tv.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastQueueTest {

    @Test
    fun createPreservesEpisodeOrderAndCurrentIndex() {
        val queue = CastQueue(listOf(
            CastQueue.Item("Episode 1", "id-1"),
            CastQueue.Item("Episode 2", "id-2"),
            CastQueue.Item("Episode 3", "id-3")
        ), 1, "site", "line", "aac")

        assertEquals(3, queue.items().size)
        assertEquals("id-2", queue.items()[1].url)
        assertEquals(1, queue.currentIndex())
        assertEquals("site", queue.siteKey())
        assertEquals("line", queue.flag())
        assertEquals("aac", queue.audioTrackFormat())
        assertTrue(queue.canAutoAdvance())
    }

    @Test
    fun createPreservesIndexesWhenAnEpisodeIsEmpty() {
        val queue = CastQueue(listOf(
            CastQueue.Item("Episode 1", "id-1"),
            CastQueue.Item("", ""),
            CastQueue.Item("Episode 3", "id-3")
        ), 2, "site", "line", null)

        assertEquals(3, queue.items().size)
        assertEquals("", queue.items()[1].url)
        assertEquals(2, queue.currentIndex())
    }

    @Test
    fun queueWithoutProviderContextDoesNotAutoAdvance() {
        val queue = CastQueue(
            listOf(CastQueue.Item("Episode 1", "id-1")),
            0, "", "line", null)

        assertFalse(queue.canAutoAdvance())
    }

    @Test
    fun selectedAudioFormatCreatesNewQueueSnapshot() {
        val original = CastQueue(
            listOf(CastQueue.Item("Episode 1", "id-1")),
            0, "site", "line", "aac")

        val changed = original.withAudioTrackFormat("ac3")

        assertEquals("aac", original.audioTrackFormat())
        assertEquals("ac3", changed.audioTrackFormat())
        assertEquals(original.items(), changed.items())
    }

    @Test
    fun currentReturnsTrackedEpisodeEvenWithoutANextEpisode() {
        val queue = CastQueue(
            listOf(CastQueue.Item("Final episode", "id-final")),
            0, "site", "line", null)

        assertTrue(queue.hasCurrent())
        assertEquals("Final episode", queue.current()!!.name)
        assertFalse(queue.canAutoAdvance())
    }
}
