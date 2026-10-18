package com.wuming.tv.core.source.config

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceBrowserTest {
    @Test fun curatedEntriesWinDedupAndSearchFindsNameOrHost() {
        val curated = SourceBrowserEntry("Primary", "https://example.org/a.json", SourceKind.VOD, SourceOrigin.CURATED)
        val duplicate = curated.copy(name = "Candidate", origin = SourceOrigin.SCREENED)
        val live = SourceBrowserEntry("News", "https://iptv.example.net/list.m3u", SourceKind.LIVE, SourceOrigin.SCREENED)
        val merged = SourceBrowser.merge(listOf(curated), listOf(duplicate, live))
        assertEquals(listOf(curated, live), merged)
        assertEquals(listOf(curated), SourceBrowser.filter(merged, "example.org", SourceKind.VOD))
        assertEquals(listOf(live), SourceBrowser.filter(merged, "news", origin = SourceOrigin.SCREENED))
    }

    @Test fun credentialValueIsNeverSearchable() {
        val protected = SourceBrowserEntry("Spring", "https://example.org/a.json?access_token=secret",
            SourceKind.VOD, SourceOrigin.SCREENED)
        assertEquals(emptyList<SourceBrowserEntry>(), SourceBrowser.filter(listOf(protected), "secret"))
        assertEquals(listOf(protected), SourceBrowser.filter(listOf(protected), "Spring"))
        assertEquals(ProtectedSourceUrl.HIDDEN_LABEL, ProtectedSourceUrl.display(protected.url))
    }
}
