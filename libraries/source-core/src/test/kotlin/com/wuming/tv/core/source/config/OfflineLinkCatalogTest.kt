package com.wuming.tv.core.source.config

import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineLinkCatalogTest {
    @Test fun onlyBrowsableHttpLinksSurviveParsing() {
        val body = """{"version":1,"verified":false,"groups":[{"name":"Live","kind":"live","entries":[
            {"name":"One","url":"https://example.org/a.m3u"},
            {"name":"Repeat","url":"https://example.org/a.m3u"},
            {"name":"Token","url":"https://example.org/b.m3u?token=secret"},
            {"name":"Local","url":"file:///tmp/local.m3u"}
        ]}]}""".toByteArray()
        val groups = OfflineLinkCatalog.parse(body)
        assertEquals("live", groups.single().kind)
        assertEquals(listOf("One", "Token"), groups.single().entries.map { it.name })
        assertEquals(true, ProtectedSourceUrl.hasCredential(groups.single().entries.last().url))
    }
}
