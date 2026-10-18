package com.wuming.tv.core.catalog

import org.junit.Assert.*
import org.junit.Test

class CatalogResultPagerTest {
    private fun cards(from: Int, count: Int) = (from until from + count).map { CatalogItem("site", "$it", "$it") }

    @Test fun fillsFortyFromTwentyItemProviderPagesAndKeepsOverflow() {
        val calls = mutableListOf<Int>()
        val pager = CatalogResultPager()
        val fetch: (Int) -> CatalogPage = { page ->
            calls += page
            CatalogPage(cards((page - 1) * 20, 20), page, 0, 0)
        }
        assertEquals(cards(0, 40), pager.load(1, 40, fetch).items)
        assertEquals(listOf(1, 2), calls)
        assertEquals(cards(40, 40), pager.load(2, 40, fetch).items)
        assertEquals(listOf(1, 2, 3, 4), calls)
        assertEquals(cards(0, 30), pager.load(1, 30, fetch).items)
        assertEquals(cards(30, 30), pager.load(2, 30, fetch).items)
        assertEquals(4, calls.size)
    }
    @Test fun partialLastPageAndRepeatingProvidersStopFetching() {
        val pager = CatalogResultPager()
        var calls = 0
        val fetch: (Int) -> CatalogPage = { page ->
            calls++
            CatalogPage(cards((page - 1) * 20, if (page == 3) 7 else 20), page, 3, 47)
        }
        assertEquals(30, pager.load(1, 30, fetch).items.size)
        val last = pager.load(2, 30, fetch)
        assertEquals(cards(30, 17), last.items)
        assertFalse(last.hasNext)
        assertEquals(47, last.total)
        assertEquals(3, calls)
        var repeats = 0
        assertEquals(20, CatalogResultPager().load(1, 40) {
            repeats++
            CatalogPage(cards(0, 20))
        }.items.size)
        assertEquals(2, repeats)
    }
    @Test fun failedFetchCanBeRetriedWithoutLosingLoadedItems() {
        val pager = CatalogResultPager()
        val calls = mutableListOf<Int>()
        try {
            pager.load(1, 40) { page ->
                calls += page
                if (page == 2) error("temporary outage")
                CatalogPage(cards(0, 20))
            }
            fail("Expected outage")
        } catch (_: IllegalStateException) { }
        val result = pager.load(1, 40) { page -> calls += page; CatalogPage(cards(20, 20), page) }
        assertEquals(cards(0, 40), result.items)
        assertEquals(listOf(1, 2, 2), calls)
    }

    @Test fun loadThroughKeepsEarlierDisplayPagesForContinuousScrolling() {
        val calls = mutableListOf<Int>()
        val pager = CatalogResultPager()
        val fetch: (Int) -> CatalogPage = { page ->
            calls += page
            CatalogPage(cards((page - 1) * 10, 10), page, 3, 30)
        }

        assertEquals(cards(0, 12), pager.loadThrough(1, 12, fetch).items)
        val second = pager.loadThrough(2, 12, fetch)
        assertEquals(cards(0, 24), second.items)
        assertEquals(2, second.page)
        assertEquals(3, second.pageCount)
        assertEquals(listOf(1, 2, 3), calls)
    }
}
