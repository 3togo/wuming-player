package com.wuming.tv.core.playback

import org.junit.Assert.*
import org.junit.Test

class BilibiliPlaybackQualitiesTest {
    private val url = "http://127.0.0.1:9978/proxy?do=bili&aid=123&cid=456&qn=32&type=mpd"
    @Test fun authenticTracksControlQualityMenuNotAdvertisedEntitlements() {
        val source = BilibiliPlaybackQualities.source(url)!!
        val response = """{"code":0,"data":{"accept_quality":[120,80,64,32],"support_formats":[{"quality":80,"new_description":"1080P"},{"quality":64,"new_description":"720P"}],"dash":{"video":[{"id":64,"height":720,"baseUrl":"https://cdn/a"},{"id":64,"height":720,"baseUrl":"https://cdn/b"},{"id":32,"height":480,"base_url":"https://cdn/c"}]}}}"""
        val result = BilibiliPlaybackQualities.parse(source, response)
        assertEquals(listOf(64,32),result.map { it.id })
        assertEquals("720P",result[0].label)
        assertTrue(result[0].url.contains("qn=64"))
        assertFalse(result[0].url.contains("qn=32"))
        assertTrue(result[0].url.contains("aid=123&cid=456"))
        assertTrue(source.apiUrl().startsWith("https://api.bilibili.com/"))
    }
    @Test fun onlyExactLocalBilibiliMpdSourcesAreEligible() {
        for (invalid in listOf(url.replace("127.0.0.1", "evil.example"), url.replace("do=bili", "do=other"),
            url.replace("aid=123", "aid=123%26evil=1"), "$url&aid=789", url.replace("type=mpd", "type=mp4"))) {
            assertNull(invalid, BilibiliPlaybackQualities.source(invalid))
        }
    }
    @Test fun anonymousResponseDoesNotInvent720p() {
        val response = """{"code":0,"data":{"accept_quality":[64,32],"dash":{"video":[{"id":32,"height":480,"baseUrl":"https://cdn/a"}]}}}"""
        assertEquals(listOf(32),BilibiliPlaybackQualities.parse(BilibiliPlaybackQualities.source(url)!!,response).map { it.id })
    }
    @Test(expected=IllegalArgumentException::class) fun loginFailureIsNotASuccessfulQualityRefresh() {
        BilibiliPlaybackQualities.parse(BilibiliPlaybackQualities.source(url)!!,"""{"code":-101}""")
    }
}
