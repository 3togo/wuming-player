package com.wuming.tv.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.ToIntFunction

class SearchResultPolicyTest {

    private val NORMALIZER = TitleNormalizer { value -> value
        .replace('無', '无')
        .replace('間', '间') }
    private val POLICY = SearchResultPolicy<Item>(Access(), NORMALIZER)

    @Test
    fun annotationConfirmsOnlyMatchingAliases() {
        val identity = TitleIdentity(
            "douban:1307914", listOf("Infernal Affairs", "无间道"), "2002", "movie")
        val plan = SearchQueryPlanner({ identity }, NORMALIZER)
            .plan("Infernal Affairs")
        val matching = item("无间道", "2002", "movie", "one")
        val unrelated = item("The Departed", "2006", "movie", "two")

        POLICY.annotate(listOf(matching, unrelated), plan, plan.variants()[0])

        assertEquals("douban:1307914", matching.canonicalId)
        assertEquals("", unrelated.canonicalId)
        assertEquals("Infernal Affairs", matching.originalQuery)
    }

    @Test
    fun strictMatchingHandlesScriptConversionAndMetadataSuffixes() {
        assertTrue(POLICY.stronglyMatches("無間道 (2002)", "无间道"))
        assertFalse(POLICY.stronglyMatches("无间道风云", "无间道"))
    }

    @Test
    fun mergeUsesCanonicalIdentityAndPreservesSourcesAndArtwork() {
        val english = item("Infernal Affairs", "2002", "movie", "one")
        english.canonicalId = "douban:1307914"
        english.aliases.add("Infernal Affairs")
        val chinese = item("无间道", "2002", "movie", "two")
        chinese.canonicalId = "douban:1307914"
        chinese.aliases.add("无间道")
        chinese.artwork = "poster.jpg"

        val merged = POLICY.merge(listOf(english, chinese))

        assertEquals(1, merged.size)
        assertEquals(listOf("one", "two"), merged[0].sources)
        assertEquals(listOf("Infernal Affairs", "无间道"), merged[0].aliases)
        assertEquals("poster.jpg", merged[0].artwork)
    }

    @Test
    fun sameTitleFromDifferentYearsRemainsSeparate() {
        assertEquals(2, POLICY.merge(listOf(
            item("The Thing", "1951", "movie", "one"),
            item("The Thing", "1982", "movie", "two"))).size)
    }

    @Test
    fun relevancePrefersExactThenAliasThenPartialMatches() {
        val exact = item("Pretty Woman", "1990", "movie", "one")
        val alias = item("风月俏佳人", "1990", "movie", "two")
        alias.aliases.add("Pretty Woman")
        val partial = item("Pretty Woman Documentary", "2020", "movie", "three")

        assertEquals(1.0, POLICY.relevanceScore(exact, "Pretty Woman"), 0.0)
        assertEquals(0.95, POLICY.relevanceScore(alias, "Pretty Woman"), 0.0)
        assertEquals(0.8, POLICY.relevanceScore(partial, "Pretty Woman"), 0.0)
    }

    @Test
    fun indexedMergeMatchesFrozenPolicyAcrossAmbiguousAndMultilingualBatches() {
        val legacy = LegacySearchResultPolicy<Item>(Access(), NORMALIZER)
        val random = Random(91403)
        val titles = arrayOf("The Thing", "ＴＨＥ Thing", "The-Thing", "TheThing", "無間道", "无间道", "A B", "AB", "", "!!!", null)
        val years = arrayOf("", " ", "1951", "1982", "１９８２", "???", null)
        val types = arrayOf("", "movie", "series", "ＱＯＶＩＥ", "???", null)
        for (round in 0 until 80) {
            val batch = mutableListOf<Item>()
            for (i in 0 until 80) {
                val item = item(titles[random.nextInt(titles.size)], years[random.nextInt(years.size)],
                    types[random.nextInt(types.size)], "source-$i")
                item.canonicalId = if (random.nextInt(3) == 0) "id-${random.nextInt(8)}" else ""
                item.aliases.add("alias-${random.nextInt(4)}")
                item.artwork = if (i % 4 == 0) "poster-$i" else ""
                item.originalQuery = if (i % 3 == 0) "query-$i" else ""
                item.matchedQuery = if (i % 5 == 0) "match-$i" else ""
                batch.add(item)
            }
            assertEquals("round $round", snapshot(legacy.merge(copy(batch))), snapshot(POLICY.merge(copy(batch))))
        }
    }

    @Test
    fun wildcardMetadataKeepsFirstCompatibleRepresentativeAndKnownIdsStaySeparate() {
        val old = item("The Thing", "1951", "movie", "old")
        val recent = item("The Thing", "1982", "movie", "recent")
        val unknown = item("TheThing", "", "", "unknown")
        val identified = item("The Thing", "1951", "movie", "identified"); identified.canonicalId = "known"
        val result = POLICY.merge(listOf(old, recent, unknown, identified))
        assertEquals(3, result.size)
        assertSame(old, result[0]); assertSame(recent, result[1])
        assertEquals(listOf("old", "unknown"), old.sources)
        assertEquals(listOf("recent"), recent.sources)
        assertSame(identified, result[2])
    }

    @Test
    fun mergeIndexesAreDiscardedBetweenCallsAndEmptyTitlesNeverMerge() {
        val first = item("!!!", "", "", "one"); val second = item("", "", "", "two")
        assertEquals(2, POLICY.merge(listOf(first, second)).size)
        first.title = "無間道"; second.title = "无间道"
        assertEquals(1, POLICY.merge(listOf(first, second)).size)
        second.title = "different"
        assertEquals(2, POLICY.merge(listOf(first, second)).size)
    }

    @Test
    fun distinctTitleNormalizationGrowsLinearlyWithInputCount() {
        for (count in intArrayOf(10, 100, 1000)) {
            val conversions = AtomicInteger()
            val normalizer = TitleNormalizer { value -> conversions.incrementAndGet(); value }
            val policy = SearchResultPolicy<Item>(Access(), normalizer)
            val input = mutableListOf<Item>()
            for (i in 0 until count) input.add(item("Unique $i", "2000", "movie", "source"))
            assertEquals(count, policy.merge(input).size)
            assertEquals(count, conversions.get())
            conversions.set(0)
            LegacySearchResultPolicy<Item>(Access(), normalizer).merge(copy(input))
            assertEquals(count * (count - 1), conversions.get())
        }
    }

    @Test
    fun preparedSortMatchesLegacyRelevanceAndPriorityIncludingStableTies() {
        val legacy = LegacySearchResultPolicy<Item>(Access(), NORMALIZER)
        val random = Random(14092)
        val words = arrayOf("Pretty Woman", "Pretty Woman Documentary", "Woman Pretty", "無間道", "无间道", "The Matrix", "", "!!!", "PRETTY-WOMAN", "Different")
        for (query in listOf(null, "", " ", "!!!", "Pretty Woman", "无间道", "ＴＨＥ Matrix", "Another query")) {
            for (round in 0 until 12) {
                val items = mutableListOf<Item>()
                for (i in 0 until 80) {
                    val item = item(words[random.nextInt(words.size)], "", "", "source-$i")
                    item.rank = random.nextInt(4) - 1
                    item.aliases.add(words[random.nextInt(words.size)])
                    item.canonicalId = if (random.nextBoolean()) "canonical" else ""
                    item.originalQuery = words[random.nextInt(words.size)]
                    items.add(item)
                }
                val expected = items.toMutableList()
                expected.sortWith { first, second ->
                    val rank = second.rank.compareTo(first.rank)
                    if (rank != 0) rank else legacy.compareRelevance(first, second, query)
                }
                POLICY.sortByRelevance(items, query, ToIntFunction { it.rank })
                for (i in items.indices) assertSame(expected[i], items[i])
            }
        }
    }

    @Test
    fun sortCachesUseObjectIdentityAndExpireAfterEachSort() {
        val first = item("Different", "", "", "one"); val second = item("Needle", "", "", "two")
        assertEquals(first, second) // Client equals may ignore site/title; cache must not.
        val items = mutableListOf(first, second)
        POLICY.sortByRelevance(items, "Needle", ToIntFunction { it.rank })
        assertSame(second, items[0])
        first.rank = 5
        POLICY.sortByRelevance(items, "Needle", ToIntFunction { it.rank })
        assertSame(first, items[0])
        first.rank = 0; first.title = "Needle"; second.title = "Different"
        POLICY.sortByRelevance(items, "Different", ToIntFunction { it.rank })
        assertSame(second, items[0])
    }

    @Test
    fun preparedSortNormalizesEachTextAndReadsEachRankOnce() {
        val conversions = AtomicInteger(); val ranks = AtomicInteger()
        val normalizer = TitleNormalizer { value -> conversions.incrementAndGet(); value }
        val policy = SearchResultPolicy<Item>(Access(), normalizer)
        val items = mutableListOf<Item>()
        for (i in 0 until 120) items.add(item("Series $i", "", "", "source"))
        policy.sortByRelevance(items, "Needle", ToIntFunction { ranks.incrementAndGet(); 0 })
        assertEquals(120, ranks.get()); assertEquals(121, conversions.get())
    }

    private fun copy(input: List<Item>): List<Item> {
        val copy = mutableListOf<Item>()
        for (value in input) {
            val item = item(value.title, value.year, value.type, ""); item.sources = value.sources.toMutableList()
            item.artwork = value.artwork; item.canonicalId = value.canonicalId
            item.aliases = value.aliases.toMutableList(); item.originalQuery = value.originalQuery; item.matchedQuery = value.matchedQuery
            copy.add(item)
        }
        return copy
    }

    private fun snapshot(items: List<Item>): List<Any> {
        val result = mutableListOf<Any>()
        for (item in items) result.add(listOf(item.title, item.year, item.type,
            item.artwork, item.canonicalId, item.aliases, item.originalQuery, item.matchedQuery, item.sources))
        return result
    }

    private fun item(title: String?, year: String?, type: String?, source: String): Item {
        val item = Item()
        item.title = title
        item.year = year
        item.type = type
        item.sources.add(source)
        return item
    }

    private class Item {
        var rank = 0
        override fun equals(other: Any?): Boolean = other is Item
        override fun hashCode(): Int = 1
        var title: String? = ""
        var year: String? = ""
        var type: String? = ""
        var artwork = ""
        var canonicalId = ""
        var aliases = mutableListOf<String>()
        var originalQuery = ""
        var matchedQuery = ""
        var sources = mutableListOf<String>()
    }

    private class Access : SearchResultAccess<Item> {
        override fun title(item: Item): String = item.title ?: ""
        override fun year(item: Item): String = item.year ?: ""
        override fun mediaType(item: Item): String = item.type ?: ""
        override fun artwork(item: Item): String = item.artwork
        override fun canonicalId(item: Item): String = item.canonicalId
        override fun aliases(item: Item): List<String> = item.aliases
        override fun originalQuery(item: Item): String = item.originalQuery
        override fun matchedQuery(item: Item): String = item.matchedQuery

        override fun assignIdentity(item: Item, canonicalId: String, aliases: List<String>,
            originalQuery: String, matchedQuery: String) {
            item.canonicalId = canonicalId
            item.aliases = aliases.toMutableList()
            item.originalQuery = originalQuery
            item.matchedQuery = matchedQuery
        }

        override fun mergeSources(target: Item, duplicate: Item) {
            for (source in duplicate.sources) {
                if (source !in target.sources) target.sources.add(source)
            }
        }

        override fun setArtwork(item: Item, artwork: String) {
            item.artwork = artwork
        }
    }
}
