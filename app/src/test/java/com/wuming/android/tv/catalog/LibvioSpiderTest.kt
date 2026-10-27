package com.wuming.android.tv.catalog

import com.google.gson.JsonParser
import com.wuming.tv.core.catalog.CatalogSource
import com.wuming.tv.core.catalog.CatVodCatalogCodec
import com.wuming.tv.core.libvio.*
import org.junit.Assert.*
import org.junit.Test

class LibvioSpiderTest {
    private class Port(var body: String) : LibvioTransport {
        var calls = 0
        var cancelled = false
        override fun get(url: String, headers: Map<String, String>): String { calls++; return body }
        override fun setCookie(url: String, cookie: String) = Unit
        override fun cancel() { cancelled = true }
    }
    private fun spider(port: Port) = LibvioSpider(LibvioCatalogSourceExecutor(LibvioSiteSpec("libvio", "Libvio"), port))
    @Test fun homeAndCategoryExposeSharedDataThroughAndroidJsonBoundary() {
        val port = Port("""<ul class="stui-header__menu"><a href="/type/1.html">Movies</a></ul>
            <a href="/detail/12.html" title="Example" data-original="/poster.jpg">Example</a>""")
        val codec = CatVodCatalogCodec(CatalogSource("libvio", "Libvio"))
        val source = spider(port)
        assertEquals("1", codec.home(source.homeContent(true)).types.single().id)
        assertEquals("Example", codec.search(source.homeVideoContent()).single().name)
        assertEquals(2, codec.category(source.categoryContent("1", "2", true, hashMapOf()), 2).page)
    }
    @Test fun pagedSearchReturnsValidEmptyJsonWithoutRepeatingFirstPage() {
        val port = Port("""{"code":1,"list":[{"id":12,"name":"Example"}]}""")
        val source = spider(port)
        val codec = CatVodCatalogCodec(CatalogSource("libvio", "Libvio"))
        assertEquals("Example", codec.search(source.searchContent("Example", false)).single().name)
        assertEquals("Example", codec.search(source.searchContent("Example", false, "1")).single().name)
        assertTrue(codec.search(source.searchContent("Example", false, "2")).isEmpty())
        assertEquals(2, port.calls)
    }
    @Test fun detailAndPlayerReturnEpisodeIdsAndMediaHeadersWithoutDownloadedCode() {
        val port = Port("<h1 class='title'>Example</h1><ul class='stui-content__playlist'><a href='/w/12-1-1.html'>One</a></ul>")
        val source = spider(port)
        val codec = CatVodCatalogCodec(CatalogSource("libvio", "Libvio"))
        val detail = codec.detail(source.detailContent(listOf("/detail/12.html")), "/detail/12.html")
        assertEquals("https://www.libvio.pw/w/12-1-1.html", detail.lines.single().episodes.single().id)
        port.body = """<script>var player_aaaa={"encrypt":3,"url":"https://media.example/v.mp4"}</script>"""
        val json = source.playerContent("Direct", detail.lines.single().episodes.single().id, emptyList())
        assertEquals(0, JsonParser.parseString(json).asJsonObject["parse"].asInt)
        val playback = codec.playback(json)
        assertTrue(playback.supported)
        assertEquals("https://www.libvio.pw/", playback.headers["Referer"])
    }
    @Test fun destructionCancelsRequestsAndAlsoWorksBeforeInitialization() {
        val port = Port("")
        spider(port).destroy()
        assertTrue(port.cancelled)
        LibvioSpider().destroy()
    }
}
