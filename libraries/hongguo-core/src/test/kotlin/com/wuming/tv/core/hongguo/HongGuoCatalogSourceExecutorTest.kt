package com.wuming.tv.core.hongguo

import com.wuming.tv.core.catalog.CatVodCatalogJson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class HongGuoCatalogSourceExecutorTest {
    private val base = HongGuoSiteSpec.BASE_URL
    private val detail = "/vod/detail/id/real-drama.html"
    private val play = "/vod/play/id/real-drama/sid/1/nid/1.html"
    private val category = "/vod/type/id/duanjudaquan.html"
    private class Transport(val pages: MutableMap<String, String>) : HongGuoTransport {
        val calls = mutableListOf<String>()
        var canceled = false
        override fun get(url: String, headers: Map<String, String>): String {
            calls += url
            assertEquals(HongGuoSiteSpec.USER_AGENT, headers["User-Agent"])
            assertEquals(HongGuoSiteSpec.BASE_URL, headers["Referer"])
            return pages[url] ?: error("Unexpected page: $url")
        }
        override fun cancel() { canceled = true }
    }
    private fun executor(vararg pages: Pair<String, String>): Pair<HongGuoCatalogSourceExecutor, Transport> {
        val transport = Transport(pages.toMap().toMutableMap())
        return HongGuoCatalogSourceExecutor(HongGuoSiteSpec("configured-key", "Configured source"), transport) to transport
    }
    private fun cards() = """<a href="$detail" title="真实剧集"><img data-original="/poster.jpg"></a>
        <a href="$detail">真实剧集</a><a href="https://foreign.example$detail">Wrong origin</a>"""
    private fun details() = """<h1>真实剧集</h1><div class="detail"><div class="pic"><img src="/poster.jpg"></div></div>
        <ul class="nav-tabs"><li><a data-target=".p-list1">主线路</a></li></ul>
        <ul class="playurl p-list1" data-more="34">
        <li><a href="$play">第01集</a></li>
        <li><a href="${play.replace("nid/1", "nid/2")}">第02集</a></li>
        <li><a href="#all">全部</a></li>
        <li><a href="https://foreign.example$play">External</a></li></ul>"""

    @Test fun currentNonNumericRoutesAndLazyPostersProduceRealHomeCards() {
        val (source, _) = executor(base to """<nav class="nav"><a href="$category">短剧大全</a></nav>${cards()}""")
        val home = source.browseHome()
        assertEquals(category, home.types.single().id)
        assertEquals("短剧大全", home.types.single().name)
        assertEquals(base.removeSuffix("/") + detail, home.items.single().id)
        assertEquals("configured-key", home.items.single().sourceKey)
        assertEquals(base + "poster.jpg", home.items.single().picture)
    }

    @Test fun currentPlaylistsRetainPublishedEpisodesAndIgnoreDisplayLimits() {
        val (source, _) = executor(base.removeSuffix("/") + detail to details())
        val result = source.detail(detail)
        assertEquals("真实剧集", result.item.name)
        assertEquals("主线路", result.lines.single().name)
        assertEquals(listOf("第01集", "第02集"), result.lines.single().episodes.map { it.name })
        // data-more is the website's display cutoff, not permission to invent episodes.
        assertEquals(2, result.lines.single().episodes.size)
        val json = JsonParser.parseString(CatVodCatalogJson.detail(result)).asJsonObject
        assertEquals("主线路", json.getAsJsonArray("list")[0].asJsonObject.get("vod_play_from").asString)
        assertTrue(json.toString().contains("nid/2.html"))
    }

    @Test fun directPlayerDataPreservesEscapedSignedQueriesWithoutFetchingMedia() {
        val media = "https://media.example/index.m3u8?sig=a%2Bb&expires=123"
        val (source, transport) = executor(base.removeSuffix("/") + play to
            """<div class="embed-responsive video" data-play="${media.replace("&", "&amp;")}"></div>""")
        val result = source.play("line-1", play)
        assertTrue(result.supported)
        assertEquals(media, result.url)
        assertEquals(listOf(base.removeSuffix("/") + play), transport.calls)
    }

    @Test fun categoryPagesUseTheirOwnPaginationAndSearchUsesThePublishedRoute() {
        val second = category.removeSuffix(".html") + "/page/2.html"
        val last = category.removeSuffix(".html") + "/page/4.html"
        val (source, _) = executor(base.removeSuffix("/") + second to
            """${cards()}<a href="$last">末页</a><a href="/vod/type/id/other/page/99.html">Unrelated</a>""",
            base + "vod/search/wd/A%20B.html" to cards())
        val page = source.category(category, 2, emptyMap())
        assertEquals(2, page.page)
        assertEquals(4, page.pageCount)
        assertEquals(1, source.search("A B").size)
    }

    @Test fun foreignPagesAndMalformedPathsAreRejectedBeforeTransport() {
        val (source, transport) = executor()
        for (id in listOf("https://foreign.example$detail", "http://www.hongguodj.cc$detail", "https://user@www.hongguodj.cc$detail", "$detail#fragment", "/vod/detail/id/../private.html")) {
            assertThrows(IllegalArgumentException::class.java) { source.detail(id) }
        }
        assertTrue(transport.calls.isEmpty())
        source.cancel()
        assertTrue(transport.canceled)
    }

    @Test fun emptyOrInvalidProviderResponsesNeverFabricateSupportedPlayback() {
        for (media in listOf("", "javascript:alert(1)", "https://media.example/watch", "https://user:secret@media.example/v.mp4", "file:///tmp/v.mp4")) {
            val (source, _) = executor(base.removeSuffix("/") + play to """<div class="video" data-play="$media"></div>""")
            assertThrows(IllegalArgumentException::class.java) { source.play("line-1", play) }
        }
        val (source, _) = executor(base to "<html></html>", base.removeSuffix("/") + detail to "<h1>Title</h1>")
        assertTrue(source.home().isEmpty())
        assertTrue(source.detail(detail).lines.isEmpty())
    }
}
