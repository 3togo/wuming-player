package com.wuming.tv.core.media.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class SubtitleQueryTest {

    @Test
    fun normalizesTitleAndClampsPage() {
        val query = SubtitleQuery(" title ", null, null, null, -1, 100)
        assertEquals("title", query.title)
        assertEquals(1, query.page)
        assertEquals(50, query.pageSize)
    }

    @Test
    fun requiresNonEmptyTitle() {
        assertThrows(IllegalArgumentException::class.java) {
            SubtitleQuery("  ", null, null, null, 1, 1)
        }
    }

    @Test
    fun movieFactoryCreatesSimpleQuery() {
        val query = SubtitleQuery.movie("Example")
        assertEquals("Example", query.title)
        assertEquals(null, query.season)
        assertEquals(null, query.episode)
        assertEquals(1, query.page)
        assertEquals(20, query.pageSize)
    }

    @Test
    fun equalityAndHashCode() {
        val a = SubtitleQuery("title", 1, 2, listOf("en"), 3, 10)
        val b = SubtitleQuery("title", 1, 2, listOf("en"), 3, 10)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}

class SubtitleCandidateTest {

    @Test
    fun normalizesFields() {
        val candidate = SubtitleCandidate(" provider ", " id ", null, null, " SRT ", null, false, 200)
        assertEquals("provider", candidate.providerId)
        assertEquals("id", candidate.id)
        assertEquals("srt", candidate.format)
        assertEquals(100, candidate.matchScore)
    }

    @Test
    fun requiresProviderAndCandidateIds() {
        assertThrows(IllegalArgumentException::class.java) {
            SubtitleCandidate("", "id", null, null, null, null, false, 50)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SubtitleCandidate("provider", "", null, null, null, null, false, 50)
        }
    }

    @Test
    fun equalityAndHashCode() {
        val uri = URI.create("https://example.com/sub.srt")
        val a = SubtitleCandidate("p", "1", "title", "en", "srt", uri, false, 90)
        val b = SubtitleCandidate("p", "1", "title", "en", "srt", uri, false, 90)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}

class SubtitlePageTest {

    @Test
    fun emptyFactory() {
        val page = SubtitlePage.empty(3)
        assertEquals(0, page.candidates.size)
        assertEquals(3, page.page)
        assertEquals(3, page.totalPages)
    }

    @Test
    fun clampsPageToOne() {
        val page = SubtitlePage(null, -5, -10)
        assertEquals(1, page.page)
        assertEquals(1, page.totalPages)
    }
}

class SubtitleSearchResultTest {

    @Test
    fun successfulWhenErrorCodeEmpty() {
        val result = SubtitleSearchResult("p", SubtitlePage.empty(1), "")
        assertTrue(result.successful)
    }

    @Test
    fun unsuccessfulWhenErrorCodePresent() {
        val result = SubtitleSearchResult("p", SubtitlePage.empty(1), "IOException")
        assertFalse(result.successful)
    }
}

class SubtitleSearchEngineTest {

    @Test
    fun isolatesProviderFailuresAndUsesDeterministicOrder() {
        val broken = object : SubtitleProvider {
            override fun id() = "z-broken"
            override fun search(query: SubtitleQuery): SubtitlePage = throw java.io.IOException("offline")
        }
        val working = object : SubtitleProvider {
            override fun id() = "a-working"
            override fun search(query: SubtitleQuery): SubtitlePage {
                val candidate = SubtitleCandidate(id(), "1", query.title, "en", "srt",
                    URI.create("https://example.com/sub.srt"), false, 90)
                return SubtitlePage(listOf(candidate), 1, 1)
            }
        }
        val engine = SubtitleSearchEngine().register(broken).register(working)

        val results = engine.search(SubtitleQuery.movie("Example"))

        assertEquals("a-working", results[0].providerId)
        assertTrue(results[0].successful)
        assertEquals(1, results[0].page.candidates.size)
        assertEquals("z-broken", results[1].providerId)
        assertFalse(results[1].successful)
        assertEquals("IOException", results[1].errorCode)
    }

    @Test
    fun registerRequiresNonBlankId() {
        assertThrows(IllegalArgumentException::class.java) {
            SubtitleSearchEngine().register(object : SubtitleProvider {
                override fun id() = ""
                override fun search(query: SubtitleQuery) = SubtitlePage.empty(1)
            })
        }
    }

    @Test
    fun unregisterRemovesProvider() {
        val provider = object : SubtitleProvider {
            override fun id() = "test"
            override fun search(query: SubtitleQuery) = SubtitlePage.empty(1)
        }
        val engine = SubtitleSearchEngine().register(provider)
        assertFalse(engine.isEmpty())
        engine.unregister("test")
        assertTrue(engine.isEmpty())
    }
}
