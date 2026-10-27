package com.wuming.tv.core.libvio

import com.wuming.tv.core.catalog.CatVodCatalogCodec
import com.wuming.tv.core.catalog.CatalogSource
import org.junit.Assert.*
import org.junit.Test

class LibvioEdgeCasesTest {
    private val base = "https://www.libvio.pw/"
    private class Port(var body: String) : LibvioTransport {
        var calls = 0
        var cancelled = false
        var location = ""
        override fun get(url: String, headers: Map<String, String>): String { calls++; location = url; return body }
        override fun setCookie(url: String, cookie: String) = Unit
        override fun cancel() { cancelled = true }
    }
    private fun executor(port: Port) = LibvioCatalogSourceExecutor(LibvioSiteSpec("x", "Libvio"), port)
    @Test fun searchUsesSuggestApiRatherThanReturningUnfilteredHtmlCards() {
        val port = Port("""{"code":1,"list":[{"id":123,"name":"Example","pic":"/poster.jpg"},
            {"id":123,"name":"Duplicate"},{"id":"../bad","name":"Invalid"},{"id":456,"name":""},null]}""")
        val result = executor(port).search(" a & b ")
        assertEquals(base + "index.php/ajax/suggest?mid=1&wd=a+%26+b&limit=10", port.location)
        assertEquals(base + "detail/123.html", result.single().id)
        assertEquals(base + "poster.jpg", result.single().picture)
    }
    @Test fun missingTitlesAreAnEmptySearchRatherThanHomeRecommendations() {
        assertTrue(executor(Port("""{"code":1,"total":0,"list":[]}""")).search("missing").isEmpty())
    }
    @Test fun apiErrorsAndMalformedSearchResponsesAreFailuresRatherThanNoMatches() {
        for (body in listOf("""{"code":0,"list":[]}""", """{"code":1}""", "<html>not json</html>")) {
            assertThrows(RuntimeException::class.java) { executor(Port(body)).search("Example") }
        }
    }
    @Test fun invalidQueriesAndPagesDoNotContactTheSite() {
        val port = Port("")
        val source = executor(port)
        for (query in listOf("   ", "x".repeat(513))) assertThrows(IllegalArgumentException::class.java) { source.search(query) }
        for (page in listOf(0, -1, 10001)) assertThrows(IllegalArgumentException::class.java) { source.category("1", page) }
        assertEquals(0, port.calls)
    }
    @Test fun readsLazyPosterFromLinkAndUsesConfiguredCategoryOrder() {
        val port = Port("""<ul class="stui-header__menu"><a href="/type/1.html">Movies</a><a href="/type/2.html">Series</a></ul>
            <a href="/detail/1.html" title="Example" data-original="/poster.jpg"><span class="pic-text">HD</span></a>""")
        val home = LibvioCatalogSourceExecutor(LibvioSiteSpec("x", "Libvio", categories = listOf("Series", "Movies")), port).browseHome()
        assertEquals(listOf("2", "1"), home.types.map { it.id })
        assertEquals(base + "poster.jpg", home.items.single().picture)
    }
    @Test fun supportsLegacyDetailsAndPreservesLineAndEpisodeOrdering() {
        val port = Port("""<div class="stui-content__detail"><h1 class="title">Legacy</h1></div>
            <ul class="stui-content__playlist"><a href="/w/12-2-2.html">Second</a><a href="/w/12-2-1.html">First</a>
            <a href="/w/12-2-1.html">Duplicate</a></ul><ul class="stui-content__playlist"><a href="/w/12-1-1.html">Other</a></ul>""")
        val detail = executor(port).detail("/detail/12.html")
        assertEquals(listOf("Second", "First"), detail.lines[0].episodes.map { it.name })
        assertEquals(2, detail.lines.size)
    }
    @Test fun cloudOnlyAndInvalidDetailPagesHaveExplicitFailures() {
        val port = Port("<div class='vod-info'><h1>Cloud only</h1></div><div class='netdisk-panel'><a href='https://pan.quark.cn/s/1'>Cloud</a></div>")
        assertTrue(assertThrows(IllegalArgumentException::class.java) { executor(port).detail("/detail/12.html") }.message!!.contains("no direct-play"))
        port.body = "<html>unrelated page</html>"
        assertThrows(IllegalArgumentException::class.java) { executor(port).detail("/detail/12.html") }
    }
    @Test fun acceptsPreviouslySavedDoubleSlashDetailIds() {
        val port = Port("<h1 class='title'>Old saved title</h1><ul class='stui-content__playlist'><a href='/w/12-1-1.html'>One</a></ul>")
        assertEquals(base + "detail/12.html", executor(port).detail(base + "/detail/12.html").item.id)
    }
    @Test fun playerSupportsJsonStringsWithBracesAndFollowingJavascript() {
        val port = Port("""<script>var player_aaaa={"url":"https://media.example/v.mp4?x=%7D","note":"brace } in a string"}; var later={"x":1};</script>""")
        assertEquals("https://media.example/v.mp4?x=%7D", executor(port).play("", "/w/12-1-1.html").url)
    }
    @Test fun playerRejectsUnknownEncodingUnsafeSchemesAndCredentialBearingUrls() {
        for (data in listOf("""{"encrypt":99,"url":"https://media.example/v.mp4"}""",
            """{"url":"file:///tmp/v.mp4"}""", """{"url":"https://user:secret@media.example/v.mp4"}""")) {
            assertThrows(RuntimeException::class.java) { executor(Port("<script>var player_aaaa=$data</script>")).play("", "/w/12-1-1.html") }
        }
    }
    @Test fun repeatedChallengeDoesNotLoopAndCancellationIsForwarded() {
        val port = Port("""var TS="123", SIG="${"a".repeat(64)}", DIFF="0", MODE="auto"; var POW = "__cdn_pow";""")
        assertThrows(IllegalArgumentException::class.java) { executor(port).home() }
        assertEquals(2, port.calls)
        executor(port).cancel()
        assertTrue(port.cancelled)
    }
    @Test fun androidProtocolRetainsMediaHeadersAndDirectParseMode() {
        val port = Port("""<script>var player_aaaa={"url":"https://media.example/v.m3u8"}</script>""")
        val playback = executor(port).play("", "/w/12-1-1.html")
        val decoded = CatVodCatalogCodec(CatalogSource("x", "Libvio")).playback(LibvioCatVodProtocol.playback(playback))
        assertTrue(decoded.supported)
        assertEquals(playback.url, decoded.url)
        assertEquals(playback.headers, decoded.headers)
    }
}
