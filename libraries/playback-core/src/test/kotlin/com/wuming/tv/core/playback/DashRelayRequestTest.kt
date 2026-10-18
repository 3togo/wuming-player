package com.wuming.tv.core.playback

import org.junit.Assert.*
import org.junit.Test

class DashRelayRequestTest {
    @Test fun manifestsFetchCompleteGetDespiteProbeAndProviderRanges() {
        for (target in listOf("http://phone/source.mpd", "http://phone/proxy?do=bili&type=mpd")) {
            for (method in listOf("GET", "HEAD")) {
                val request = DashRelayPolicy.upstreamRequest(target, target,
                    mapOf("rAnGe" to "bytes=99-", "If-Range" to "old-etag", "Cookie" to "fixture"),
                    method, "bytes=0-7", "application/dash+xml")
                assertEquals("GET", request.method)
                assertNull(request.header("Range"))
                assertNull(request.header("If-Range"))
                assertEquals("fixture", request.header("Cookie"))
                assertEquals("application/dash+xml", request.header("Accept"))
            }
        }
    }

    @Test fun mediaChildrenKeepHeadAndExactRangesWithBilibiliContext() {
        val root = "http://phone/proxy?do=bili&type=mpd"
        for (method in listOf("GET", "HEAD")) {
            val request = DashRelayPolicy.upstreamRequest(root, "https://upos.bilivideo.com/audio.m4s",
                mapOf("Range" to "bytes=99-", "Cookie" to "private", "Authorization" to "private"),
                method, "bytes=100-200", null)
            assertEquals(method, request.method)
            assertEquals("bytes=100-200", request.header("Range"))
            assertEquals("https://www.bilibili.com/", request.header("Referer"))
            assertNotNull(request.header("User-Agent"))
            assertNull(request.header("Cookie"))
            assertNull(request.header("Authorization"))
        }
    }
}
