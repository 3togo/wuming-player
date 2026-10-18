package com.wuming.tv.core.search

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchQueryPlannerTest {

    private val NORMALIZER = TitleNormalizer { value -> value
        .replace('無', '无')
        .replace('間', '间')
        .replace('風', '风')
        .replace('變', '变')
        .replace('鳳', '凤') }

    @Test
    fun traditionalQuerySearchesOriginalAndSimplified() {
        val plan = SearchQueryPlanner.localOnly(NORMALIZER).plan("無間道")

        assertEquals(listOf("無間道", "无间道"), texts(plan))
    }

    @Test
    fun simplifiedQueryIsNotExpandedBackToTraditional() {
        val plan = SearchQueryPlanner.localOnly(NORMALIZER).plan("无间道")

        assertEquals(listOf("无间道"), texts(plan))
    }

    @Test
    fun resolvedIdentityAddsCanonicalAliases() {
        val identity = TitleIdentity(
            "douban:1307914",
            listOf("Infernal Affairs", "无间道", "無間道"),
            "2002",
            "movie")
        val plan = SearchQueryPlanner({ identity }, NORMALIZER)
            .plan("Infernal Affairs")

        assertEquals(listOf("Infernal Affairs", "无间道"), texts(plan))
        assertEquals("id:douban:1307914", plan.canonicalKey())
    }

    @Test
    fun variantsAreBoundedAndDeduplicated() {
        val identity = TitleIdentity(
            "wikidata:Q207954",
            listOf("Pretty Woman", "风月俏佳人", "漂亮女人", "麻雀變鳳凰"),
            "1990",
            "movie")

        val plan = SearchQueryPlanner({ identity }, NORMALIZER)
            .plan("Pretty Woman")

        assertEquals(listOf("Pretty Woman", "风月俏佳人", "漂亮女人"), texts(plan))
    }

    private fun texts(plan: SearchQueryPlan): List<String> {
        return plan.variants().map { it.text() }
    }
}
