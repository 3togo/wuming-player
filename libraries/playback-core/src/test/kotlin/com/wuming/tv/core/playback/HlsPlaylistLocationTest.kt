package com.wuming.tv.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsPlaylistLocationTest {

    private val PROXY = "http://127.0.0.1:9978/proxy?do=m3u8&url=https%3A%2F%2Fvip.ffzy-online3.com%2Fepisode%2Findex.m3u8"

    @Test
    fun shouldPurifyDetectsM3u8Urls() {
        assertTrue(HlsPlaylistLocation.shouldPurify(PROXY))
        assertTrue(HlsPlaylistLocation.shouldPurify(PROXY.replace("127.0.0.1", "localhost")))
        assertTrue(HlsPlaylistLocation.shouldPurify(PROXY.replace("127.0.0.1", "[::1]")))
        assertTrue(HlsPlaylistLocation.shouldPurify("http://127.0.0.1:9978/live/index.m3u8"))
        assertTrue(HlsPlaylistLocation.shouldPurify("https://example.com/index.m3u8"))
        assertFalse(HlsPlaylistLocation.shouldPurify(null))
        assertFalse(HlsPlaylistLocation.shouldPurify("http://127.0.0.1:9978/m3u8"))
        assertFalse(HlsPlaylistLocation.shouldPurify(PROXY.replace("do=m3u8", "do=other")))
        assertFalse(HlsPlaylistLocation.shouldPurify("http://127.0.0.1:9978/proxy?do=stream&url=movie.mp4"))
    }

    @Test
    fun ruleBaseUrlResolvesProviderPlaylistOrigin() {
        assertEquals("https://vip.ffzy-online3.com/episode/", HlsPlaylistLocation.ruleBaseUrl(PROXY))
        assertEquals("http://127.0.0.1:9978/", HlsPlaylistLocation.baseUrl(PROXY))
    }

    @Test
    fun ruleBaseUrlHandlesUnescapedUrl() {
        val unescaped = "http://127.0.0.1:9978/proxy?do=m3u8&url=https://media.example/nested/index.m3u8"
        assertEquals("http://127.0.0.1:9978/", HlsPlaylistLocation.baseUrl(unescaped))
        assertEquals("https://media.example/nested/", HlsPlaylistLocation.ruleBaseUrl(unescaped))
    }

    @Test
    fun ruleBaseUrlHandlesRemoteM3u8() {
        val remote = "https://cdn.example/hls/index.m3u8"
        assertEquals("https://cdn.example/hls/", HlsPlaylistLocation.ruleBaseUrl(remote))
        assertEquals("http://127.0.0.1:9978/", HlsPlaylistLocation.ruleBaseUrl(PROXY.replace("do=m3u8", "do=other")))
    }

    @Test
    fun ruleBaseUrlWithInitialUrl() {
        val localChild = "http://127.0.0.1:9978/nested/index.m3u8"
        assertEquals("https://vip.ffzy-online3.com/episode/", HlsPlaylistLocation.ruleBaseUrl(localChild, PROXY))

        val remoteChild = "https://cdn.example/hls/segment.m3u8"
        assertEquals("https://cdn.example/hls/", HlsPlaylistLocation.ruleBaseUrl(remoteChild, PROXY))

        val proxiedChild = "http://127.0.0.1:9978/proxy?do=m3u8&url=https%3A%2F%2Fcdn.example%2Fepisode%2Findex.m3u8"
        assertEquals("https://cdn.example/episode/", HlsPlaylistLocation.ruleBaseUrl(proxiedChild, PROXY))
    }
}
