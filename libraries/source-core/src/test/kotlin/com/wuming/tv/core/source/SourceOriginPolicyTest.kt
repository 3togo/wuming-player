package com.wuming.tv.core.source

import com.wuming.tv.core.source.config.TvBoxConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceOriginPolicyTest {

    private fun config(vararg fields: Pair<String, Any?>) = TvBoxConfig(raw = mapOf(*fields))

    @Test
    fun prefersTheMostSpecificOriginField() {
        val decoded = config(
            "url" to "https://backup.example.test",
            "homeUrl" to "https://origin.example.test",
            "主页url" to "https://first.example.test/more",
        )
        assertEquals("https://first.example.test/more", SourceOriginPolicy.originOf(decoded))
    }

    @Test
    fun usesTheLeadingTokenOfAFieldThatCarriesNotes() {
        val decoded = config("主页url" to "https://origin.example.test  （首页）")
        assertEquals("https://origin.example.test", SourceOriginPolicy.originOf(decoded))
    }

    @Test
    fun ignoresFieldsThatAreNotRemoteUrls() {
        assertEquals(null, SourceOriginPolicy.originOf(config("url" to "/local/path", "host" to "ftp://x")))
        assertEquals(null, SourceOriginPolicy.originOf(config()))
    }

    @Test
    fun treatsAppLocalOriginsAsNotForProbing() {
        assertTrue(SourceOriginPolicy.isLocal("http://127.0.0.1:9978/push"))
        assertTrue(SourceOriginPolicy.isLocal("http://LocalHost/push"))
        assertFalse(SourceOriginPolicy.isLocal("https://127.0.0.1.example.test"))
        assertEquals(SourceOriginPolicy.Verdict.LOCAL,
            SourceOriginPolicy.verdict("http://127.0.0.1:9978/x", reachable = false))
    }

    @Test
    fun classifiesProbeOutcomes() {
        assertEquals(SourceOriginPolicy.Verdict.NO_ORIGIN, SourceOriginPolicy.verdict(null, true))
        assertEquals(SourceOriginPolicy.Verdict.UNREACHABLE,
            SourceOriginPolicy.verdict("https://origin.example.test", false))
        assertEquals(SourceOriginPolicy.Verdict.ALIVE,
            SourceOriginPolicy.verdict("https://origin.example.test", true, "<html><body>片源仓库</body></html>"))
    }

    @Test
    fun labelsAnAnswersButParkedDomain() {
        val parked = "<html><head><title>Domain is parked</title></head>"
        assertEquals(SourceOriginPolicy.Verdict.PARKED,
            SourceOriginPolicy.verdict("https://origin.example.test", true, parked))
        assertTrue(SourceOriginPolicy.isParked("<p>此域名可能已废弃 domain may be for sale</p>"))
    }

    @Test
    fun doesNotReadParkingTextFromTheRestOfALongPage() {
        val body = "x".repeat(SourceOriginPolicy.PARKING_SCAN_LIMIT) + "this domain is for sale"
        assertFalse(SourceOriginPolicy.isParked(body))
    }

    @Test
    fun matchesParkingTextRegardlessOfLetterCase() {
        assertTrue(SourceOriginPolicy.isParked("<H1>DOMAIN MAY BE AVAILABLE</H1>"))
    }
}
