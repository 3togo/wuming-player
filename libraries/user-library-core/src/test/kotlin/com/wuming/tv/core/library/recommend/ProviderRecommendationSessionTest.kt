package com.wuming.tv.core.library.recommend

import org.junit.Assert.*
import org.junit.Test

class ProviderRecommendationSessionTest {
    @Test fun retainsOrderAcrossPagesButDropsDeletedHistory() {
        val session = ProviderRecommendationSession()
        assertEquals(listOf("A", "B"), session.seeds("source", 1, listOf(" A ", "B", "A", ""), false))
        assertEquals(listOf("A", "B"), session.seeds("source", 2, listOf("New", "B", "A"), false))
        assertEquals(listOf("B"), session.seeds("source", 3, listOf("New", "B"), false))
        assertEquals(listOf("New", "B"), session.seeds("source", 1, listOf("New", "B"), false))
        assertEquals(emptyList<String>(), session.seeds("source", 2, listOf("B"), true))
        assertEquals(emptyList<String>(), session.seeds("source", 3, emptyList(), false))
        assertEquals(10, session.seeds("other", 1, (0..20).map(Int::toString), false).size)
        assertEquals(listOf("B"), session.seeds("source", 1, listOf("B"), false))
    }
    @Test fun limitsAndScopeAdmission() {
        assertTrue(ProviderRecommendationSession.applies("csp_Douban", "Other", "guess_you_like"))
        assertFalse(ProviderRecommendationSession.applies("csp_Douban", "Other", "movie"))
        assertFalse(ProviderRecommendationSession.applies("csp_Other", "Other", "guess_you_like"))
        val session = ProviderRecommendationSession()
        assertEquals(emptyList<String>(), session.seeds("a", 1, listOf("x\nsecret", "x".repeat(513)), false))
        session.seeds("a", 1, listOf("old"), false)
        session.clear()
        assertEquals(listOf("new"), session.seeds("a", 2, listOf("new"), false))
    }
}
