package com.wuming.tv.core.catalog

import org.junit.Assert.*
import org.junit.Test

class CatalogVerificationTest {
    private val item = CatalogItem(id = "one", name = "One")
    private val detail = CatalogDetail(item, lines = listOf(CatalogLine("direct", "Direct", listOf(CatalogEpisode("1", "ep")))))

    @Test fun probeEarnsCatalogueEvidenceWithoutExecutingPlayback() {
        val seen = mutableSetOf<CatalogCapability>()
        var closed = false
        val provider = object : CatalogSourceExecutor, AutoCloseable {
            override val source = CatalogSource("unknown", "Unknown")
            override fun browseHome() = CatalogHome(listOf(item), listOf(CatalogType("movie")))
            override fun category(typeId: String, page: Int, filters: Map<String, String>) = CatalogPage(listOf(item))
            override fun search(query: String) = listOf(item)
            override fun detail(id: String) = detail
            override fun play(flag: String, id: String): CatalogPlayback = error("Probe must never request playback")
            override fun close() { closed = true }
        }
        VerifyingCatalogSourceExecutor(provider, { seen += it }).use {
            CatalogVerificationProbe.check(it, true, false, "One")
        }
        val evidence = CatalogVerification(seen.associateWith { 100L })
        assertTrue(evidence.catalogVerified(100))
        assertTrue(evidence.discoveryVerified(100))
        assertFalse(CatalogCapability.PLAYBACK_URL in seen)
        assertTrue(closed)
    }

    @Test fun emptyResponsesAndFailedPlaybackCannotEarnEvidence() {
        val seen = mutableSetOf<CatalogCapability>()
        val provider = object : CatalogSourceExecutor {
            override val source = CatalogSource()
            override fun search(query: String) = emptyList<CatalogItem>()
            override fun detail(id: String) = CatalogDetail(item)
            override fun play(flag: String, id: String) = CatalogPlayback(supported = false, url = "https://example.test/video")
        }
        val wrapper = VerifyingCatalogSourceExecutor(provider, { seen += it })
        CatalogVerificationProbe.check(wrapper, true, false, "anything")
        wrapper.detail("one")
        wrapper.play("direct", "ep")
        assertTrue(seen.isEmpty())
    }

    @Test fun expiryIsPerCapabilityAndFutureEvidenceDoesNotQualify() {
        val now = CatalogVerification.MAX_AGE_MILLIS + 200
        val evidence = CatalogVerification(mapOf(CatalogCapability.LISTING to now,
            CatalogCapability.DETAIL to 100, CatalogCapability.PLAYBACK_URL to now + 1))
        assertFalse(evidence.catalogVerified(now))
        assertEquals(setOf(CatalogCapability.LISTING), evidence.fresh(now))
    }

    @Test fun observationPreservesResultsExceptionsAndResourceCleanup() {
        var resourceClosed = false
        val failure = IllegalStateException("provider error")
        val provider = object : CatalogSourceExecutor, AutoCloseable {
            override val source = CatalogSource()
            override fun search(query: String) = listOf(item)
            override fun detail(id: String): CatalogDetail = throw failure
            override fun play(flag: String, id: String) = CatalogPlayback(true, "https://example.test/video")
            override fun close(): Unit = throw failure
        }
        val seen = mutableSetOf<CatalogCapability>()
        val wrapper = VerifyingCatalogSourceExecutor(provider, { seen += it; error("storage unavailable") }, { resourceClosed = true })
        assertEquals(listOf(item), wrapper.search("one"))
        assertSame(failure, runCatching { wrapper.detail("one") }.exceptionOrNull())
        assertTrue(wrapper.play("direct", "one").supported)
        assertTrue(CatalogCapability.PLAYBACK_URL in seen)
        runCatching { wrapper.close() }
        assertTrue(resourceClosed)
    }

    @Test fun searchResultsWithoutEpisodeDetailsDoNotQualify() {
        assertFalse(CatalogVerification(mapOf(CatalogCapability.LISTING to 1L,
            CatalogCapability.SEARCH to 1L)).catalogVerified(1))
    }
}
