package com.wuming.tv.core.libvio

import com.wuming.tv.core.catalog.CatalogSource
import com.wuming.tv.core.catalog.CatVodCatalogCodec
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class LibvioCatalogSourceExecutorTest {
    private val base = "https://www.libvio.pw/"
    private class Fixture(var body: String) : LibvioTransport {
        var requested = ""
        var cookie = ""
        override fun get(url: String, headers: Map<String, String>): String { requested = url; return body }
        override fun setCookie(url: String, cookie: String) { this.cookie = cookie }
    }
    private fun executor(port: LibvioTransport) = LibvioCatalogSourceExecutor(LibvioSiteSpec("libvio", "Libvio"), port)
    private val detail = """
        <div class="vod-poster"><img id="js-poster-img" data-original="/poster.jpg" src="/loading.gif"></div>
        <div class="vod-info"><h1>Example &amp; Title</h1></div>
        <span class="detail-content">Full synopsis</span><div class="vod-meta"><span class="meta-item">2026</span></div>
        <div class="playlist-panel"><h3>Direct</h3><ul class="stui-content__playlist">
        <li><a href="/w/123-1-1.html">Episode 1</a></li><li><a href="/w/123-1-2.html">Episode 2</a></li>
        <li><a href="https://ad.example/w/123-1-3.html">Advertisement</a></li></ul></div>
        <div class="playlist-panel netdisk-panel"><h3>Cloud</h3><a href="https://pan.quark.cn/s/123">Download</a></div>
    """.trimIndent()
    @Test fun detailsKeepDirectEpisodesAndMetadataWithoutCloudOrAdvertisements() {
        val value = executor(Fixture(detail)).detail("/detail/123.html")
        assertEquals("Example & Title", value.item.name)
        assertEquals(base + "poster.jpg", value.item.picture)
        assertEquals("2026", value.item.year)
        assertEquals("Full synopsis", value.content)
        assertEquals(2, value.lines.single().episodes.size)
        assertEquals(base + "w/123-1-1.html", value.lines.single().episodes.first().id)
        val decoded = CatVodCatalogCodec(CatalogSource("libvio", "Libvio")).detail(LibvioCatVodProtocol.detail(value), "123")
        assertEquals(value.lines.single().episodes, decoded.lines.single().episodes)
    }
    @Test fun homeDeduplicatesCardsAndPreservesLazyPostersAndTypes() {
        val port = Fixture("""<ul class="stui-header__menu"><a href="/type/1.html">Movies</a></ul>
            <a href="/detail/123.html" title="Example"><img data-original="/poster.jpg"><span class="pic-text">HD</span></a>
            <a href="/detail/123.html">Example</a><a href="https://ad.example/detail/9.html">Ad</a>""")
        val home = executor(port).browseHome()
        assertEquals("1", home.types.single().id)
        assertEquals(base + "poster.jpg", home.items.single().picture)
        assertEquals("HD", home.items.single().remarks)
    }
    @Test fun searchEscapesQueryAndCategoryUsesSitePagination() {
        val port = Fixture("""{"code":1,"list":[]}""")
        val source = executor(port)
        source.search("a & b")
        assertEquals(base + "index.php/ajax/suggest?mid=1&wd=a+%26+b&limit=10", port.requested)
        port.body = "<ul class='stui-page'><a href='/type/1-9.html'>Last</a></ul>"
        assertEquals(9, source.category("1", 2).pageCount)
        assertEquals(base + "type/1-2.html", port.requested)
    }
    @Test fun resolvesCurrentDirectEncodingAndPreservesSignedQuery() {
        val port = Fixture("""<script>var player_aaaa={"encrypt":3,"url":"https://media.example/video.mp4?sig=a+b%2Fc"}; var unrelated={"other":true};</script>""")
        val result = executor(port).play("Direct", "/w/123-1-1.html")
        assertTrue(result.supported)
        assertEquals("https://media.example/video.mp4?sig=a+b%2Fc", result.url)
        assertEquals(base, result.headers["Referer"])
        assertFalse(result.headers.containsKey("Cookie"))
    }
    @Test fun decodesPercentAndBase64PlayerUrlsWithoutEvaluatingScripts() {
        val port = Fixture("""<script>var player_aaaa={"encrypt":1,"url":"https%3A%2F%2Fmedia.example%2Fv.m3u8%3Fsig%3Da%2Bb"}</script>""")
        assertEquals("https://media.example/v.m3u8?sig=a+b", executor(port).play("", "/w/123-1-1.html").url)
        port.body = """<script>var player_aaaa={"encrypt":2,"url":"aHR0cHM6Ly9tZWRpYS5leGFtcGxlL3YubXA0"}</script>"""
        assertEquals("https://media.example/v.mp4", executor(port).play("", "/w/123-1-1.html").url)
    }
    @Test fun rejectsOffsitePageRequestsAndNonMediaPlayerResults() {
        val port = Fixture("""<script>var player_aaaa={"url":"https://player.example/index.html"}</script>""")
        assertThrows(IllegalArgumentException::class.java) { executor(port).detail("https://elsewhere.example/detail/123.html") }
        assertThrows(IllegalArgumentException::class.java) { executor(port).play("", "/w/123-1-1.html") }
        assertThrows(IllegalArgumentException::class.java) { executor(port).category("../secret", 1) }
        assertThrows(IllegalArgumentException::class.java) { LibvioSiteSpec.from("x", "x", """{"siteUrl":"http://127.0.0.1/"}""") }
    }
    @Test fun solvesOnlyThePublishedAutomaticChallengeAndRetriesOnce() {
        val sig = "a".repeat(64)
        var cookie = ""
        val port = object : LibvioTransport {
            override fun get(url: String, headers: Map<String, String>): String {
                assertEquals("\"Windows\"", headers["Sec-CH-UA-Platform"])
                return if (cookie.isEmpty()) """<script>var TS = "123", SIG = "$sig", DIFF = "0", MODE = "auto"; var POW = "__cdn_pow";</script>""" else "<html>content</html>"
            }
            override fun setCookie(url: String, value: String) { cookie = value }
        }
        assertEquals("<html>content</html>", LibvioBrowserSession(port).get(base))
        val nonce = cookie.substringAfter("123_auto_").substringBefore('_')
        assertEquals(0, MessageDigest.getInstance("SHA-256").digest((sig + nonce).toByteArray())[0].toInt() and 0xf0)
    }
    @Test fun rejectsInteractiveAndExcessiveDifficultyChallenges() {
        val port = Fixture("""var TS = "123", SIG = "${"a".repeat(64)}", DIFF = "00000", MODE = "auto"; var POW = "__cdn_pow";""")
        assertThrows(IllegalStateException::class.java) { LibvioBrowserSession(port).get(base) }
        port.body = port.body.replace("00000", "0").replace("auto", "slider")
        assertThrows(IllegalArgumentException::class.java) { LibvioBrowserSession(port).get(base) }
        assertEquals("", port.cookie)
    }
    @Test fun selectsOnlyKnownLibvioIdentities() {
        assertTrue(LibvioSiteSpec.matches("csp_Libvio"))
        assertTrue(LibvioSiteSpec.matches("csp_LibvioGuard"))
        assertTrue(LibvioSiteSpec.matches("builtin_libvio"))
        assertFalse(LibvioSiteSpec.matches("csp_LibvioOther"))
    }
}
