package com.wuming.tv.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SearchResultEntriesTest {
    private data class Entry(val source: String, val id: String, val title: String)
    private fun distinct(items: List<Entry>) = SearchResultEntries.distinct(items, { it.source }, { it.id })

    @Test fun allKeepsEverySourceAndVideoDespiteMatchingTitles() {
        val first = Entry("first", "1", "愛的迫降")
        val second = Entry("second", "1", "愛的迫降")
        val alternate = Entry("first", "2", "愛的迫降")
        assertEquals(listOf(first, second, alternate), distinct(listOf(first, second, alternate)))
    }

    @Test fun repeatedBatchesKeepOneEntryAndPreserveArrivalOrder() {
        val first = Entry("first", "1", "愛的迫降")
        val second = Entry("second", "1", "爱的迫降")
        val repeated = first.copy(title = "爱的迫降")
        val result = distinct(listOf(first, second, repeated, second))
        assertEquals(listOf(first, second), result)
        assertSame(first, result.first())
    }

    @Test fun allContainsEveryFilteredSourceEntry() {
        val first = Entry("first", "1", "愛的迫降")
        val second = Entry("second", "1", "愛的迫降")
        val coordinator = SourceSearchCoordinator<String, Entry>(
            { it }, { it }, { sites, _ -> sites }, { it }, { it.source }, { it.title }, ::distinct)
        coordinator.begin(listOf("first", "second"), null)
        coordinator.add(listOf(first))
        coordinator.add(listOf(second, second))
        assertEquals(listOf(first), coordinator.selectSource("first"))
        assertEquals(listOf(second, second), coordinator.selectSource("second"))
        coordinator.showAllSources()
        assertEquals(setOf(first, second), coordinator.allResults().toSet())
        assertEquals(2, coordinator.allResults().size)
    }
}
