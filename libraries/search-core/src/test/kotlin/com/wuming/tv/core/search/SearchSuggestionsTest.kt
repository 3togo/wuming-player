package com.wuming.tv.core.search

import org.junit.Assert.*
import org.junit.Test

class SearchSuggestionsTest {
    @Test fun `splits multilingual words without losing the original title`() {
        assertEquals(listOf("星際旅行：下一代", "星際旅行", "下一代"), SearchSuggestions.local(" 星際旅行：下一代 "))
        assertEquals(listOf("Star Trek", "Star", "Trek"), SearchSuggestions.local("Star Trek"))
        assertTrue(SearchSuggestions.local(null).isEmpty())
    }

    @Test fun `suggests base and next explicit season in Chinese and English`() {
        assertEquals(listOf("旅程第两季", "旅程", "旅程第三季"), SearchSuggestions.local("旅程第两季"))
        assertEquals(listOf("旅程第二十九季", "旅程", "旅程第三十季"), SearchSuggestions.local("旅程第二十九季"))
        assertEquals(listOf("旅程第9部", "旅程", "旅程第10部"), SearchSuggestions.local("旅程第9部"))
        assertTrue(SearchSuggestions.local("Journey S09").contains("Journey S10"))
        assertTrue(SearchSuggestions.local("Journey Season 2").contains("Journey Season 3"))
    }

    @Test fun `does not guess seasons from years or numeric movie titles`() {
        for (title in listOf("2001", "1917", "Blade Runner 2049", "Apollo 13", "旅程99", "旅程第九十九季")) {
            assertFalse(SearchSuggestions.local(title).any { it.endsWith("100季") || it.endsWith("2050") || it.endsWith("14") })
        }
        assertEquals(listOf("2001"), SearchSuggestions.local("2001"))
    }

    @Test fun `malformed remote entries do not discard other suggestions`() {
        assertEquals(listOf("A", "B", "C"), SearchSuggestions.parseResponse(
            """{"data":[null,4,{}, {"title":" A "}, {"title":{},"name":"B"},{"title":"","name":"C"},{"title":"A"}]}""",
        ))
        assertTrue(SearchSuggestions.parseResponse("invalid").isEmpty())
        assertTrue(SearchSuggestions.parseResponse("null").isEmpty())
    }

    @Test fun `merging preserves local choices and bounds deduplicated remote results`() {
        val result = SearchSuggestions.merge("旅程第二季", listOf("旅程", " ") + (1..40).map { "Title $it" })
        assertEquals(listOf("旅程第二季", "旅程", "旅程第三季"), result.take(3))
        assertEquals(20, result.size)
        assertEquals(result.size, result.distinct().size)
    }

    @Test fun `manual season suggestions never broaden automatic query planning`() {
        assertEquals(1, SearchQueryPlanner.localOnly().plan("旅程第二季").variants().size)
    }
}
