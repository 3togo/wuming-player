package com.wuming.tv.core.library

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserLibraryPolicyTest {

    @Test
    fun contentKeysPreserveEmbeddedDelimitersAndStripMatchingCatalogSuffix() {
        val delimiter = "@@@"
        assertEquals("site", ContentKeyCodec.sourceKey("site@@@video@@@part", delimiter))
        assertEquals("video@@@part", ContentKeyCodec.contentId("site@@@video@@@part", delimiter))
        assertEquals("video@@@part",
            ContentKeyCodec.historyContentId("site@@@video@@@part@@@7", 7, delimiter))
        assertEquals("video@@@part@@@8",
            ContentKeyCodec.historyContentId("site@@@video@@@part@@@8", 7, delimiter))
        assertEquals("plain", ContentKeyCodec.historyContentId("plain", 7, delimiter))
    }

    @Test
    fun libraryVisibilityFiltersUnavailableAndHiddenThenRanks() {
        val low = Item("source-a", "low")
        val high = Item("source-a", "high")
        val unavailable = Item("source-b", "other")
        val hidden = Item("source-a", "hidden")
        val ranks = mapOf("low" to 1, "high" to 9, "other" to 10, "hidden" to -1)
        val policy = UserLibraryPolicy<Item>(ItemAccess())

        assertEquals(listOf(high, low), policy.visibleAndRanked(
            listOf(low, unavailable, hidden, high), setOf("source-a")) { ranks[it] ?: 0 })
        assertEquals(listOf(unavailable, high, low), policy.visibleAndRanked(
            listOf(low, unavailable, hidden, high), emptySet()) { ranks[it] ?: 0 })
    }

    @Test
    fun historyRulesHandleUnknownDurationsAndSyncBoundary() {
        assertFalse(HistoryPolicy.canSave(0))
        assertTrue(HistoryPolicy.canSave(1))
        assertFalse(HistoryPolicy.canSync(6_000, 1_000))
        assertTrue(HistoryPolicy.canSync(6_001, 1_000))
        assertFalse(HistoryPolicy.shouldMerge("same", 1_000, "same", 1_000, false))
        assertTrue(HistoryPolicy.shouldMerge("old", -1, "new", 999_999, false))
        assertTrue(HistoryPolicy.shouldMerge("old", 1_000, "new", 601_000, false))
        assertFalse(HistoryPolicy.shouldMerge("old", 1_000, "new", 601_001, false))
    }

    @Test
    fun retentionNormalizesAndCyclesAtBothEnds() {
        val policy = HistoryRetentionPolicy()
        assertArrayEquals(intArrayOf(20, 40, 60, 80, 100), policy.allowedLimits)
        assertEquals(40, policy.normalize(41))
        assertEquals(3, policy.itemsToRemove(43, 40))
        assertEquals(0, policy.itemsToRemove(39, 40))
        assertEquals(20, policy.next(100))
        assertEquals(100, policy.previous(20))
        assertEquals(40, policy.percentage(40))
    }

    @Test
    fun viewingProgressRejectsSuspendedGapsAndPreservesKnownDuration() {
        assertEquals(500, ViewingProgressPolicy.elapsedWatchTime(true, 1_000, 1_500))
        assertEquals(0, ViewingProgressPolicy.elapsedWatchTime(true, 1_000, 20_000))
        assertEquals(0, ViewingProgressPolicy.elapsedWatchTime(false, 1_000, 1_500))

        val progress = ViewingProgressPolicy.update(
            1_000, 500, 9_000, 9_100, 10_000, -1, false)
        assertEquals(1_500, progress.watchedDurationMillis)
        assertEquals(10_000, progress.mediaDurationMillis)
        assertTrue(progress.completed)
        assertTrue(ViewingProgressPolicy.shouldPersist(11_000, 1_000))
        assertTrue(ViewingProgressPolicy.matches("key", null, "key", ""))
    }

    private data class Item(val source: String, val key: String)

    private class ItemAccess : LibraryItemAccess<Item> {
        override fun key(item: Item): String = item.key
        override fun sourceKey(item: Item): String = item.source
    }
}
