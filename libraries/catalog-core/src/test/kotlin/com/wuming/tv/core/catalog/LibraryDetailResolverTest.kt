package com.wuming.tv.core.catalog

import org.junit.Assert.*
import org.junit.Test

class LibraryDetailResolverTest {
    @Test fun recoversHistoryByEquivalentTitleAndPreservesOriginalErrorOnFailedReplacement() {
        val original = IllegalStateException("stale ID")
        var failReplacement = false
        val source = object : CatalogSourceExecutor {
            override val source = CatalogSource("site", "Site")
            override fun search(query: String) = listOf(
                CatalogItem("site", "unrelated", "Another title"),
                CatalogItem("site", "new", "Example: Show"))
            override fun detail(id: String): CatalogDetail {
                if (id == "old") throw original
                if (failReplacement) throw IllegalArgumentException("replacement failed")
                return CatalogDetail(item = CatalogItem("site", id, "Example Show"))
            }
            override fun play(flag: String, id: String) = CatalogPlayback()
        }
        val gateway = ExecutorCatalogGateway(listOf(source))
        val item = CatalogItem("site", "old", "Example Show")
        assertEquals("new", LibraryDetailResolver.resolve(gateway, item, true).item.id)
        failReplacement = true
        assertSame(original, assertThrows(IllegalStateException::class.java) {
            LibraryDetailResolver.resolve(gateway, item, true)
        })
    }
}
