package com.wuming.tv.core.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class RemoteSpiderCatalogSourceExecutorTest {

    @Test
    fun executesTheAndroidTypeFourQueryDialect() {
        val calls = mutableListOf<Map<String, String>>()
        val executor = RemoteSpiderCatalogSourceExecutor(
            RemoteSpiderSiteSpec(
                "remote", "Remote", "https://example.test/vod", "site-extension",
                mapOf("User-Agent" to "TV"), listOf("Movies"),
            ),
            transport = RemoteSpiderTransport { api, headers, params ->
            assertEquals("https://example.test/vod", api)
            assertEquals("TV", headers["User-Agent"])
            calls += params
            when {
                params["filter"] == "true" -> """{"class":[{"type_id":"movie","type_name":"Movies"}],"list":[]}"""
                params["t"] != null -> """{"page":2,"pagecount":3,"list":[{"vod_id":"category","vod_name":"Category"}]}"""
                params["wd"] != null -> """{"list":[{"vod_id":"found","vod_name":"Found"}]}"""
                params["ids"] != null -> """{"list":[{"vod_id":"found","vod_name":"Found","vod_play_from":"line","vod_play_url":"Episode${'$'}stream-id"}]}"""
                else -> """{"parse":0,"url":"https://media.example/video.m3u8","header":{"Referer":"https://example.test/"}}"""
            }
            },
        )

        assertEquals("Movies", executor.browseHome().types.single().name)
        assertEquals("category", executor.category("movie", 2, mapOf("year" to "2026")).items.single().id)
        assertEquals("found", executor.search("probe").single().id)
        val detail = executor.detail("found")
        val playback = executor.play(detail.lines.single().flag, detail.lines.single().episodes.single().id)
        assertTrue(playback.supported)
        assertEquals("https://media.example/video.m3u8", playback.url)
        assertEquals("site-extension", calls.single { it["t"] != null }["extend"])
        val filters = String(
            Base64.getUrlDecoder().decode(calls.single { it["t"] != null }.getValue("ext")),
            StandardCharsets.UTF_8,
        )
        assertEquals("{\"year\":\"2026\"}", filters)
    }

    @Test
    fun resolvesUrlExtensionDuringHomeLoad() {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        val executor = RemoteSpiderCatalogSourceExecutor(
            RemoteSpiderSiteSpec(
                "remote", "Remote", "https://example.test/vod", "https://example.test/ext.txt",
            ),
            transport = RemoteSpiderTransport { api, _, params ->
                calls += api to params
                if (api.endsWith("ext.txt")) "resolved-extension"
                else """{"class":[],"list":[]}"""
            },
        )

        executor.browseHome()

        assertEquals("https://example.test/ext.txt", calls[0].first)
        assertTrue(calls[0].second.isEmpty())
        assertEquals("resolved-extension", calls[1].second["extend"])
    }
}
