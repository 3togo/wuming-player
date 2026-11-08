package com.wuming.tv.core.catalog

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class CachingCatalogSourceExecutorTest {
    private class Source : CatalogSourceExecutor, AutoCloseable {
        override val source = CatalogSource("same-key", "Test")
        var searches = 0
        var categories = 0
        var details = 0
        var plays = 0
        var homes = 0
        var browseHomes = 0
        var cancellations = 0
        var failSearch = false
        var lastFilters: Map<String, String> = emptyMap()
        var closed = false
        override fun home(): List<CatalogItem> { homes++; return listOf(CatalogItem(name = "home")) }
        override fun browseHome(): CatalogHome { browseHomes++; return CatalogHome(listOf(CatalogItem(name = "browse"))) }
        override fun search(query: String): List<CatalogItem> {
            searches++
            check(!failSearch) { "search unavailable" }
            return listOf(CatalogItem(name = query))
        }
        override fun category(typeId: String, page: Int, filters: Map<String, String>): CatalogPage {
            lastFilters = filters
            categories++; return CatalogPage()
        }
        override fun detail(id: String): CatalogDetail { details++; return CatalogDetail() }
        override fun play(flag: String, id: String): CatalogPlayback { plays++; return CatalogPlayback() }
        override fun playbackPreparationSafety(flag: String, id: String) = PlaybackPreparationSafety.READ_ONLY_STREAM
        override fun cancel() { cancellations++ }
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

    @Test fun homeOperationsRemainDistinctAndExpireAtTheBoundary() {
        val source = Source()
        val clock = MutableCacheClock()
        val cache = CachingCatalogSourceExecutor(source, clock = clock)
        repeat(2) {
            assertEquals("home", cache.home().single().name)
            assertEquals("browse", cache.browseHome().items.single().name)
        }
        assertEquals(1, source.homes)
        assertEquals(1, source.browseHomes)
        clock.millisNow += 299_999
        cache.home()
        cache.browseHome()
        assertEquals(1, source.homes)
        assertEquals(1, source.browseHomes)
        clock.millisNow++
        cache.home()
        cache.browseHome()
        assertEquals(2, source.homes)
        assertEquals(2, source.browseHomes)
    }

    @Test fun categoryKeysIncludeTypePageAndSnapshotOfFilters() {
        val source = Source()
        val cache = CachingCatalogSourceExecutor(source)
        val filters = linkedMapOf("year" to "2025", "area" to "HK")
        cache.category("movie", 1, filters)
        filters["year"] = "2026"
        assertEquals("2025", source.lastFilters["year"])
        cache.category("movie", 1, mapOf("area" to "HK", "year" to "2025"))
        assertEquals(1, source.categories)
        cache.category("movie", 1, filters)
        cache.category("movie", 2, filters)
        cache.category("tv", 2, filters)
        assertEquals(4, source.categories)
    }

    @Test fun accountChangeAndCancellationInvalidateEveryMetadataOperation() {
        val source = Source()
        var account = 0L
        val cache = CachingCatalogSourceExecutor(source, { account })
        fun loadAll() {
            cache.home(); cache.browseHome(); cache.category("movie", 1, emptyMap())
            cache.search("query"); cache.detail("id")
        }
        loadAll()
        account++
        loadAll()
        cache.cancel()
        loadAll()
        assertEquals(listOf(3, 3, 3, 3, 3), listOf(
            source.homes, source.browseHomes, source.categories, source.searches, source.details,
        ))
        assertEquals(1, source.cancellations)
        assertSame(source.source, cache.source)
        assertEquals(PlaybackPreparationSafety.READ_ONLY_STREAM, cache.playbackPreparationSafety("line", "id"))
        repeat(2) { assertNotNull(cache.prewarm("line", "https://example.test/video.mp4")) }
        assertEquals(2, source.plays)
    }

    @Test fun failedSearchCanRecoverAndSearchAndDetailKeysRemainIndependent() {
        val source = Source()
        val cache = CachingCatalogSourceExecutor(source)
        source.failSearch = true
        assertEquals("search unavailable", assertThrows(IllegalStateException::class.java) {
            cache.search("same")
        }.message)
        source.failSearch = false
        cache.search("same"); cache.search("other"); cache.search("same")
        cache.detail("same"); cache.detail("other"); cache.detail("same")
        assertEquals(3, source.searches)
        assertEquals(2, source.details)
    }
}

private class MutableCacheClock(var millisNow: Long = 1_800_000_000_000) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = Instant.ofEpochMilli(millisNow)
}
