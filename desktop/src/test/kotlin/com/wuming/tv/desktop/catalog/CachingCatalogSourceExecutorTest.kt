package com.wuming.tv.desktop.catalog

import com.wuming.tv.core.catalog.*
import com.wuming.tv.desktop.cache.MutableCacheClock
import org.junit.Assert.*
import org.junit.Test

class CachingCatalogSourceExecutorTest {
    private class Source : CatalogSourceExecutor, AutoCloseable {
        override val source = CatalogSource("same-key", "Test")
        var searches = 0
        var categories = 0
        var details = 0
        var plays = 0
        var closed = false
        override fun search(query: String): List<CatalogItem> { searches++; return listOf(CatalogItem(name = query)) }
        override fun category(typeId: String, page: Int, filters: Map<String, String>): CatalogPage {
            categories++; return CatalogPage()
        }
        override fun detail(id: String): CatalogDetail { details++; return CatalogDetail() }
        override fun play(flag: String, id: String): CatalogPlayback { plays++; return CatalogPlayback() }
        override fun close() { closed = true }
    }

    @Test fun keysTtlsAndPlaybackBypass() {
        val clock = MutableCacheClock()
        val source = Source()
        CachingCatalogSourceExecutor(source, clock = clock).use { cache ->
            repeat(2) {
                cache.search("test")
                cache.detail("id")
                cache.category("type", 1, mapOf("a" to "1", "b" to "2"))
                cache.play("line", "episode")
            }
            cache.category("type", 1, linkedMapOf("b" to "2", "a" to "1"))
            assertEquals(1, source.searches)
            assertEquals(1, source.details)
            assertEquals(1, source.categories)
            assertEquals(2, source.plays)
            cache.category("type", 2, mapOf("a" to "1"))
            assertEquals(2, source.categories)
            clock.millisNow += 120_000
            cache.search("test")
            cache.detail("id")
            assertEquals(2, source.searches)
            assertEquals(1, source.details)
            clock.millisNow += 480_000
            cache.detail("id")
            assertEquals(2, source.details)
        }
        assertTrue(source.closed)
    }

    @Test fun refreshAccountsAndNewRuntimeInvalidateResults() {
        var account = 0L
        val source = Source()
        val cache = CachingCatalogSourceExecutor(source, { account })
        cache.search("test")
        account++
        cache.search("test")
        cache.clear()
        cache.search("test")
        assertEquals(3, source.searches)
        val replacement = Source()
        CachingCatalogSourceExecutor(replacement).search("test")
        assertEquals(1, replacement.searches)
    }
}
