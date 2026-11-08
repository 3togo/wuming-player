package com.wuming.android.tv.catalog

import com.wuming.tv.core.catalog.CatVodCatalogCodec
import com.wuming.tv.core.catalog.CatalogSource
import com.wuming.tv.core.hongguo.*
import org.junit.Assert.*
import org.junit.Test

class HongGuoSpiderTest {
    private class Port(var body: String) : HongGuoTransport {
        var calls = 0
        var canceled = false
        override fun get(url: String, headers: Map<String, String>): String { calls++; return body }
        override fun cancel() { canceled = true }
    }
    private fun source(port: Port) = HongGuoSpider(HongGuoCatalogSourceExecutor(HongGuoSiteSpec("key", "HongGuo"), port))
    private val codec = CatVodCatalogCodec(CatalogSource("key", "HongGuo"))

    @Test fun catalogueDetailAndPlaybackReachAndroidThroughTheSharedAdapter() {
        val port = Port("""<h1>Programme</h1><ul class="playurl"><a href="/vod/play/id/drama/sid/1/nid/1.html">One</a></ul>""")
        val source = source(port)
        val detail = codec.detail(source.detailContent(listOf("/vod/detail/id/drama.html")), "drama")
        assertEquals("Programme", detail.item.name)
        assertEquals(1, detail.lines.size)
        port.body = """<div class="video" data-play="https://media.example/v.m3u8?sig=a%2Bb&amp;x=1"></div>"""
        val playback = codec.playback(source.playerContent("line-1", detail.lines.single().episodes.single().id, emptyList()))
        assertTrue(playback.supported)
        assertEquals("https://media.example/v.m3u8?sig=a%2Bb&x=1", playback.url)
        assertEquals(HongGuoSiteSpec.BASE_URL, playback.headers["Referer"])
    }

    @Test fun laterSearchPagesAreEmptyAndDestructionCancelsTheTransport() {
        val port = Port("""<a href="/vod/detail/id/drama.html">Programme</a>""")
        val source = source(port)
        assertEquals(1, codec.search(source.searchContent("Programme", false, "1")).size)
        assertTrue(codec.search(source.searchContent("Programme", false, "2")).isEmpty())
        assertEquals(1, port.calls)
        source.destroy()
        assertTrue(port.canceled)
        HongGuoSpider().destroy()
    }
}
