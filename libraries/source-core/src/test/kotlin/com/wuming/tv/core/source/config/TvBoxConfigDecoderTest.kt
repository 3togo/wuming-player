package com.wuming.tv.core.source.config

import com.wuming.tv.core.source.RecordOutcome
import com.wuming.tv.core.source.SectionOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvBoxConfigDecoderTest {

    @Test
    fun decodeStringParsesSites() {
        val json = """{"sites":[{"key":"demo","name":"Demo","type":3,"api":"csp_Demo"}]}"""
        val config = TvBoxConfigDecoder.decode(json)
        assertEquals(1, config.sites.size)
        assertEquals("demo", config.sites[0].key)
        assertEquals("Demo", config.sites[0].name)
    }

    @Test
    fun decodeStringParsesLives() {
        val json = """{"lives":[{"name":"Live","url":"https://example.com/live.m3u8","type":0}]}"""
        val config = TvBoxConfigDecoder.decode(json)
        assertEquals(1, config.lives.size)
        assertEquals("Live", config.lives[0].name)
        assertEquals("https://example.com/live.m3u8", config.lives[0].url)
    }

    @Test
    fun decodeStringParsesAdRules() {
        val json = """{"rules":[{"name":"rule1","hosts":["example.com"],"regex":["ad.*"]}]}"""
        val config = TvBoxConfigDecoder.decode(json)
        assertEquals(1, config.adRules.size)
        assertEquals("rule1", config.adRules[0].name)
        assertEquals(listOf("example.com"), config.adRules[0].hosts)
        assertEquals(listOf("ad.*"), config.adRules[0].regex)
    }

    @Test
    fun decodeStringParsesSpider() {
        val json = """{"spider":"https://example.com/spider.jar"}"""
        val config = TvBoxConfigDecoder.decode(json)
        assertEquals("https://example.com/spider.jar", config.spider)
    }

    @Test
    fun decodeStringEmptySitesReturnsEmptyList() {
        val json = """{"sites":[]}"""
        val config = TvBoxConfigDecoder.decode(json)
        assertTrue(config.sites.isEmpty())
    }

    @Test
    fun decodeStringWithBlankSpiderReturnsNull() {
        val json = """{"spider":"  "}"""
        val config = TvBoxConfigDecoder.decode(json)
        assertNull(config.spider)
    }

    @Test
    fun decodeStringClassifiesSiteRuntime() {
        val json = """{"sites":[{"key":"js","name":"JS","type":3,"api":"https://example.com/source.js"}]}"""
        val config = TvBoxConfigDecoder.decode(json)
        assertEquals(com.wuming.tv.core.source.TvBoxSourceRuntime.DIRECT_JAVASCRIPT, config.sites[0].runtime)
    }

    @Test
    fun decodeStringParsesSiteHeader() {
        val json = """{"sites":[{"key":"h","name":"H","type":0,"api":"https://api.example.com","header":{"User-Agent":"TestAgent"}}]}"""
        val config = TvBoxConfigDecoder.decode(json)
        assertEquals(mapOf("User-Agent" to "TestAgent"), config.sites[0].header)
    }

    @Test
    fun decodeStringParsesSiteCategories() {
        val json = """{"sites":[{"key":"c","name":"C","type":0,"api":"https://api.example.com","categories":["movie","series"]}]}"""
        val config = TvBoxConfigDecoder.decode(json)
        assertEquals(listOf("movie", "series"), config.sites[0].categories)
    }

    @Test
    fun decodeJsonObjectParsesSites() {
        val obj = com.google.gson.JsonParser.parseString(
            """{"sites":[{"key":"obj","name":"Obj","type":0,"api":"https://api.example.com"}]}"""
        ).asJsonObject
        val config = TvBoxConfigDecoder.decode(obj)
        assertEquals(1, config.sites.size)
        assertEquals("obj", config.sites[0].key)
    }

    @Test
    fun decodeStringRefusesSiteEntriesThatHaveNoKey() {
        val json = """{"sites":[{"key":"good","name":"Good","type":0,"api":"https://api.example.com"},{"bad":"entry"}]}"""
        val config = TvBoxConfigDecoder.decode(json)
        assertEquals(1, config.sites.size)
        assertEquals("good", config.sites[0].key)
        // The refused entry stays on record instead of vanishing from the parsed document.
        val admission = config.admissions.first { it.name == "sites" }
        assertEquals(
            listOf(RecordOutcome.KEPT, RecordOutcome.BLANK_KEY),
            admission.verdicts.map { it.outcome },
        )
    }

    @Test
    fun decodeStringReportsEverySectionItLookedFor() {
        val json = """{"sites":[{"key":"good","name":"Good","type":0,"api":"https://api.example.com"}],"lives":[]}"""
        val config = TvBoxConfigDecoder.decode(json)
        assertEquals(
            mapOf("sites" to SectionOutcome.PRESENT, "lives" to SectionOutcome.EMPTY, "rules" to SectionOutcome.MISSING),
            config.admissions.associate { it.name to it.outcome },
        )
    }

    @Test
    fun decodeStringParsesFallbackSourceForJsSite() {
        val json = """{"sites":[{"key":"js","name":"JS","type":3,"api":"https://example.com/source.js"}]}"""
        val config = TvBoxConfigDecoder.decode(json)
        assertNotNull(config.sites[0].fallbackSource)
        assertEquals(com.wuming.tv.core.source.TvBoxSourceRuntime.DIRECT_JAVASCRIPT, config.sites[0].fallbackSource!!.kind)
    }
}
