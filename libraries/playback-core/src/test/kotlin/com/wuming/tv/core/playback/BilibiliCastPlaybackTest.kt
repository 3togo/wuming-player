package com.wuming.tv.core.playback

import org.junit.Assert.*
import org.junit.Test

class BilibiliCastPlaybackTest {
    private val proxy = "http://127.0.0.1:9978/proxy?do=bili&aid=123&cid=456&type=mpd&qn=80"
    private val source = BilibiliCastPlayback.source(proxy)!!
    private val response = """{"code":0,"data":{"quality":64,"format":"mp4720","accept_quality":[80,64],"accept_description":["1080P","720P"],"durl":[{"url":"https://cdn.bilivideo.com/video.mp4?token=fixture"}]}}"""

    @Test fun resolvesRealProgressiveQualityAndUsesFixedApiAndPublicMediaHeaders() {
        var calls = 0
        val stream = BilibiliCastPlayback.resolve(proxy, null) { url, headers ->
            calls++
            assertTrue(url.startsWith("https://api.bilibili.com/x/player/playurl?avid=123&cid=456&qn=80&fnval=1&"))
            assertEquals(setOf("Referer", "User-Agent"), headers.keys)
            response
        }!!
        assertEquals(1, calls)
        assertEquals(64, stream.quality)
        assertEquals("720P", stream.label)
        assertTrue(stream.url.startsWith("https://cdn.bilivideo.com/"))
    }

    @Test fun recognizesOnlyTheOwnedProxyAndRejectsMalformedOrForeignSources() {
        val lan = proxy.replace("127.0.0.1", "192.168.43.123")
        assertNotNull(BilibiliCastPlayback.source(lan, "http://192.168.43.123:9978"))
        assertNull(BilibiliCastPlayback.source(lan, "http://192.168.43.124:9978"))
        assertNull(BilibiliCastPlayback.source(lan, "http://192.168.43.123:9979"))
        for (url in listOf(proxy + "&qn=32", proxy.replace("aid=123", "aid=123%26evil=1"),
            proxy.replace("type=mpd", "type=mp4"), proxy.replace("http://", "http://user:secret@"),
            proxy + "#fragment", proxy.replace("qn=80", "qn=invalid"))) {
            assertNull(url, BilibiliCastPlayback.source(url))
        }
        assertNull(BilibiliCastPlayback.resolve("https://other.example/a.mpd", null) { _, _ -> fail("Unexpected API call"); "" })
    }

    @Test fun rejectsApiFailuresSplitFilesUntrustedHostsAndUnexpectedQualityUpgrades() {
        for (bad in listOf(response.replace("\"code\":0", "\"code\":-101"),
            response.replace("mp4720", "flv720"),
            response.replace("cdn.bilivideo.com", "bilivideo.com.evil.example"),
            response.replace("https://cdn.", "https://user:secret@cdn."),
            response.replace("\"quality\":64", "\"quality\":120"),
            response.replace("}]", "},{\"url\":\"https://cdn.bilivideo.com/second.mp4\"}]"),
            "{", "{}", """{"code":0,"data":{"dash":{}}}""")) {
            assertNull(BilibiliCastPlayback.parse(source, bad))
        }
    }
}
