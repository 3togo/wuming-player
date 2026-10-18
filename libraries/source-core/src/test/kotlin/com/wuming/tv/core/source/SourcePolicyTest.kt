package com.wuming.tv.core.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SourcePolicyTest {

    private val ACCESS: SourceAccess<Source> = Access()
    private val CATALOG = SourceCatalogPolicy<Source>(ACCESS)
    private val RANKING = SourceRankingPolicy<Source>(ACCESS)

    @Test
    fun catalogueDeduplicatesByStableKeyAndAppendsRequiredSource() {
        val first = source("one", true, true)
        val duplicate = source("one", false, false)
        val required = source("builtin", false, false)

        assertEquals(listOf(first), CATALOG.distinct(listOf(first, duplicate)))
        assertEquals(listOf(first, required),
            CATALOG.withRequiredSource(listOf(first, duplicate, required), required))
    }

    @Test
    fun preferredSelectionFallsBackToFirstAndHandlesEmptyCatalogue() {
        val first = source("one", true, true)
        val second = source("two", true, false)

        assertEquals(second, CATALOG.selectPreferred(listOf(first, second), "two"))
        assertEquals(first, CATALOG.selectPreferred(listOf(first, second), "missing"))
        assertNull(CATALOG.selectPreferred(emptyList(), "missing"))
    }

    @Test
    fun searchableSelectionHonorsCapabilitiesAndEnabledKeys() {
        val first = source("one", true, true)
        val second = source("two", true, false)
        val hidden = source("three", false, true)

        assertEquals(listOf(first, second), CATALOG.searchable(listOf(first, second, hidden), null))
        assertEquals(listOf(first),
            CATALOG.searchable(listOf(first, second, hidden), setOf("one", "three")))
        assertEquals(listOf(first), CATALOG.quickSearchable(listOf(first, second, hidden), null))
    }

    @Test
    fun rankingUsesMetricsOnlyAfterMinimumSampleCount() {
        val fast = source("fast", true, true)
        val reliable = source("reliable", true, true)
        val untested = source("untested", true, true)
        val metrics = mapOf(
            "fast" to SourceMetric(3, 9_000_000.0, 0.7, 0.85),
            "reliable" to SourceMetric(3, 2_000_000.0, 0.95, 0.65),
            "untested" to SourceMetric(2, 20_000_000.0, 1.0, 1.0))

        assertEquals(listOf(fast, reliable, untested),
            RANKING.rankByPerformance(listOf(untested, reliable, fast), metrics))
        assertEquals(listOf(reliable, fast, untested),
            RANKING.rankByReliability(listOf(untested, fast, reliable), metrics))
        assertEquals(listOf(fast, reliable, untested),
            RANKING.rankBySpeed(listOf(untested, reliable, fast), metrics))
        assertEquals(listOf(fast, reliable),
            RANKING.reliableSources(listOf(untested, reliable, fast), metrics, 0.7))
    }

    @Test
    fun performanceMathIsPortableAndBounded() {
        assertEquals(0.5, SourcePerformancePolicy.reliability(2, 4), 0.0)
        assertEquals(50.0, SourcePerformancePolicy.percentage(2, 4), 0.0)
        assertEquals(1.0, SourcePerformancePolicy.performance(1.0, 20_000_000.0), 0.0)
        assertTrue(SourcePerformancePolicy.reliable(7, 10))
        assertEquals("2.0 MB/s", SourcePerformancePolicy.formatSpeed((2.0 * 1024 * 1024), Locale.ROOT))
    }

    private fun source(key: String, searchable: Boolean, quick: Boolean): Source {
        return Source(key, searchable, quick)
    }

    private data class Source(val key: String, val searchable: Boolean, val quick: Boolean)

    private class Access : SourceAccess<Source> {
        override fun key(source: Source): String = source.key
        override fun displayName(source: Source): String = source.key
        override fun api(source: Source): String = ""
        override fun extension(source: Source): String = ""
        override fun jar(source: Source): String = ""
        override fun type(source: Source): Int = 0
        override fun timeoutMillis(source: Source): Long = 0
        override fun hidden(source: Source): Boolean = false
        override fun indexable(source: Source): Boolean = true
        override fun searchable(source: Source): Boolean = source.searchable
        override fun changeable(source: Source): Boolean = true
        override fun quickSearch(source: Source): Boolean = source.quick
        override fun categories(source: Source): List<String> = emptyList()
        override fun headers(source: Source): Map<String, String> = emptyMap()
    }
}
