package com.wuming.tv.core.playback

import org.junit.Assert.*
import org.junit.Test

class JsonPlaybackResponseTest {
    @Test fun primitiveHeaderValuesKeepTheirExistingWireRepresentations() {
        val result = JsonPlaybackResponse.decode("""{"url":"https://media.test/v","data":{"header":{"X-Count":7}},"header":{"X-Enabled":true,"X-Bad":[],"X-Null":null}}""")
        assertEquals(mapOf("X-Count" to "7", "X-Enabled" to "true"), result.headers)
        assertThrows(JsonPlaybackResponse.DecodeException::class.java) {
            JsonPlaybackResponse.decode("""{"url":7}""")
        }
    }

    @Test fun nestedHeadersMergeWithRootAliasesAndInheritedAuthentication() {
        val fallback = mapOf("cookie" to "auth=ok", "X-Token" to "inherited", "User-Agent" to "old")
        val result = JsonPlaybackResponse.decode("""{
            "data":{"url":"//cdn.test/v","header":"{\"referer\":\"https://cms.test/\",\"x-token\":\"nested\"}"},
            "header":{"X-Token":"root","Cookie":null},"UA":"new","Origin":"https://origin.test"
        }""", fallback, "https://parser.test/")
        assertEquals("https://cdn.test/v", result.url)
        assertEquals(mapOf("Cookie" to "auth=ok", "Referer" to "https://cms.test/", "X-Token" to "root", "User-Agent" to "new", "Origin" to "https://origin.test"), result.headers)
        assertEquals("old", fallback["User-Agent"])
    }

    @Test fun rootUrlWinsAndNullOrMalformedOptionalDataDoesNotBreakIt() {
        listOf("null", "[]", "4", "\"bad\"").forEach { data ->
            assertEquals("https://a.test/v", JsonPlaybackResponse.decode("""{"url":"https://a.test/v","data":$data,"header":"not json"}""").url)
        }
        assertEquals("http://cdn.test/v", JsonPlaybackResponse.decode("""{"url":"//cdn.test/v"}""", endpoint = "http://parser.test").url)
    }

    @Test fun rejectsBadBodiesErrorTextExecutableUrlsAndHeaderInjection() {
        listOf("null", "[]", "bad").forEach { body ->
            assertEquals(JsonPlaybackResponse.Failure.INVALID_JSON, assertThrows(JsonPlaybackResponse.DecodeException::class.java) { JsonPlaybackResponse.decode(body) }.failure)
        }
        listOf("service unavailable", "https:/missing-host", "javascript:alert(1)", "data:text/html,hello", "").forEach { url ->
            assertEquals(JsonPlaybackResponse.Failure.INVALID_URL, assertThrows(JsonPlaybackResponse.DecodeException::class.java) { JsonPlaybackResponse.decode("""{"url":"$url"}""") }.failure)
        }
        val result = JsonPlaybackResponse.decode("""{"url":"https://a.test/v","header":{"X-Bad":"one\r\ntwo","bad name":"value","X-Good":"ok"}}""")
        assertEquals(mapOf("X-Good" to "ok"), result.headers)
    }

    @Test fun hostsRetainTheirOwnNativeTransportAdmission() {
        assertFalse(JsonPlaybackResponse.isMediaUrl("custommedia://host/play"))
        assertTrue(JsonPlaybackResponse.isMediaUrl("custommedia://host/play", null))
        assertFalse(JsonPlaybackResponse.isMediaUrl("javascript:alert(1)", null))
    }

    @Test fun keepsPlayerTransportAndSegmentAdmission() {
        listOf("file:///video.mp4", "content://media/1", "proxy://stream/1", "rtsp://host/live", "https://a.test/one|||1000***https://a.test/two|||2000").forEach {
            assertTrue(it, JsonPlaybackResponse.isMediaUrl(it))
        }
    }
}
