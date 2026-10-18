package com.wuming.tv.core.catalog

import org.junit.Assert.*
import org.junit.Test

class CatalogPaginationTest {
    @Test fun pagesCoverEveryItemOnceIncludingPartialLastPage() {
        val items = (0 until 53).toList()
        val pages = (0 until CatalogPagination.pageCount(items.size, 24)).map { CatalogPagination.slice(items, 24, it) }
        assertEquals(listOf(24, 24, 5), pages.map { it.size })
        assertEquals(items, pages.flatten())
        assertEquals(pages.last(), CatalogPagination.slice(items, 24, 99))
    }
    @Test fun emptyAndInvalidPreferencesRemainSafe() {
        assertEquals(1, CatalogPagination.pageCount(0, 0))
        assertTrue(CatalogPagination.slice(emptyList<Int>(), 0, -1).isEmpty())
        assertEquals(6, CatalogPagination.size(-1))
        assertEquals(120, CatalogPagination.size(Int.MAX_VALUE))
    }
}
