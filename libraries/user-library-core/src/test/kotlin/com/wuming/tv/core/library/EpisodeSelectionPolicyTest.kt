package com.wuming.tv.core.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeSelectionPolicyTest {
    @Test
    fun `resume requires exact line and episode and a nonnegative position`() {
        val saved = VideoLibraryEntry(lineFlag = "line", episodeId = "one", positionMillis = 60000)
        assertEquals(60000L, EpisodeSelectionPolicy.resumePosition(saved, "line", "one"))
        assertEquals(0L, EpisodeSelectionPolicy.resumePosition(saved, "other", "one"))
        assertEquals(0L, EpisodeSelectionPolicy.resumePosition(saved, "line", "two"))
        assertEquals(0L, EpisodeSelectionPolicy.resumePosition(null, "line", "one"))
        assertEquals(0L, EpisodeSelectionPolicy.resumePosition(saved.copy(positionMillis = -1), "line", "one"))
    }

    @Test
    fun `restores stable line and episode identifiers`() {
        assertEquals(
            EpisodeSelectionIndex(1, 1),
            EpisodeSelectionPolicy.restore(
                listOf("line-a", "line-b"),
                listOf(listOf("a1"), listOf("b1", "b2")),
                "line-b",
                "b2",
            ),
        )
    }

    @Test
    fun `falls back to first playable episode when provider catalogue changes`() {
        assertEquals(
            EpisodeSelectionIndex(0, 0),
            EpisodeSelectionPolicy.restore(listOf("new"), listOf(listOf("new-1")), "gone", "gone"),
        )
        assertNull(EpisodeSelectionPolicy.restore(emptyList(), emptyList(), null, null))
    }

    @Test
    fun `advances only when another episode exists`() {
        assertEquals(2, EpisodeAdvancePolicy.nextIndex(1, 4))
        assertNull(EpisodeAdvancePolicy.nextIndex(3, 4))
        assertNull(EpisodeAdvancePolicy.nextIndex(-1, 4))
        assertNull(EpisodeAdvancePolicy.nextIndex(4, 4))
        assertEquals(0, EpisodeAdvancePolicy.previousIndex(1, 4))
        assertNull(EpisodeAdvancePolicy.previousIndex(0, 4))
        assertNull(EpisodeAdvancePolicy.previousIndex(4, 4))
        assertEquals(EpisodeAdvancePolicy.Direction.NEXT, EpisodeAdvancePolicy.playbackDirection(true, false))
        assertEquals(EpisodeAdvancePolicy.Direction.PREVIOUS, EpisodeAdvancePolicy.playbackDirection(true, true))
        assertEquals(EpisodeAdvancePolicy.Direction.NEXT, EpisodeAdvancePolicy.playbackDirection(false, true))
    }
}
