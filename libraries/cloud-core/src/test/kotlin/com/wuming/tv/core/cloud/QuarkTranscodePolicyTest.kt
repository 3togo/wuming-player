package com.wuming.tv.core.cloud

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class QuarkTranscodePolicyTest {

    private fun video(resolution: String, url: String = "https://example.com/v.mp4",
                      width: Long = 0, height: Long = 0, accessable: Boolean = true): JsonObject {
        val video = JsonObject()
        video.addProperty("resolution", resolution)
        val info = JsonObject()
        info.addProperty("url", url)
        if (width > 0) info.addProperty("width", width)
        if (height > 0) info.addProperty("height", height)
        video.add("video_info", info)
        if (!accessable) video.addProperty("accessable", false)
        return video
    }

    private fun videos(vararg items: JsonObject): JsonArray {
        val array = JsonArray()
        items.forEach { array.add(it) }
        return array
    }

    @Test
    fun transcodeResolutionMapsFlags() {
        assertEquals("", QuarkTranscodePolicy.transcodeResolution(null))
        assertEquals("", QuarkTranscodePolicy.transcodeResolution("夸父原"))
        assertEquals("", QuarkTranscodePolicy.transcodeResolution("normal"))
        assertEquals("auto", QuarkTranscodePolicy.transcodeResolution("夸父智"))
        assertEquals("auto", QuarkTranscodePolicy.transcodeResolution("夸克智"))
        assertEquals("auto", QuarkTranscodePolicy.transcodeResolution("quark智"))
        assertEquals("low", QuarkTranscodePolicy.transcodeResolution("quark普画"))
        assertEquals("auto", QuarkTranscodePolicy.transcodeResolution("QUARK智"))
    }

    @Test
    fun selectTranscodeExactResolutionMatch() {
        val videos = videos(video("super"), video("low"))
        assertEquals("low", QuarkTranscodePolicy.selectTranscode(videos, "low")
            .get("resolution").asString)
    }

    @Test
    fun selectTranscodeAutoPicksHighestSmartQuality() {
        val videos = videos(video("super"), video("high"), video("normal"), video("low"))
        assertEquals("super", QuarkTranscodePolicy.selectTranscode(videos, "auto")
            .get("resolution").asString)
    }

    @Test
    fun selectTranscodeAutoSkipsInaccessible() {
        val videos = videos(video("super", accessable = false), video("high"))
        assertEquals("high", QuarkTranscodePolicy.selectTranscode(videos, "auto")
            .get("resolution").asString)
    }

    @Test
    fun selectTranscodeAutoFallsBackToLow() {
        val videos = videos(video("super", accessable = false), video("low"))
        assertEquals("low", QuarkTranscodePolicy.selectTranscode(videos, "auto")
            .get("resolution").asString)
    }

    @Test
    fun selectTranscodeAutoThrowsSmartQualityExceptionWhenEmpty() {
        assertThrows(OpenDriveFailure::class.java) {
            QuarkTranscodePolicy.selectTranscode(JsonArray(), "auto")
        }
    }

    @Test
    fun selectTranscodeManualReturnsBestWhenExactNotFound() {
        val videos = videos(video("low"))
        assertEquals("low", QuarkTranscodePolicy.selectTranscode(videos, "4k")
            .get("resolution").asString)
    }

    @Test
    fun selectTranscodeManualThrowsIOExceptionWhenEmpty() {
        assertThrows(IOException::class.java) {
            QuarkTranscodePolicy.selectTranscode(JsonArray(), "4k")
        }
    }

    @Test
    fun supportedSmartQualityRespects1080pCeiling() {
        assertTrue(QuarkTranscodePolicy.supportedSmartQuality(video("super", width = 1920, height = 1080)))
        assertFalse(QuarkTranscodePolicy.supportedSmartQuality(video("4k", width = 3840, height = 2160)))
    }

    @Test
    fun smartTranscodeRankUsesPixelArea() {
        val rank1920x1080 = QuarkTranscodePolicy.smartTranscodeRank(video("super", width = 1920, height = 1080))
        val rank1280x720 = QuarkTranscodePolicy.smartTranscodeRank(video("high", width = 1280, height = 720))
        assertTrue(rank1920x1080 > rank1280x720)
    }

    @Test
    fun transcodeRankOrdersCorrectly() {
        assertTrue(QuarkTranscodePolicy.transcodeRank("4k") > QuarkTranscodePolicy.transcodeRank("2k"))
        assertTrue(QuarkTranscodePolicy.transcodeRank("2k") > QuarkTranscodePolicy.transcodeRank("super"))
        assertTrue(QuarkTranscodePolicy.transcodeRank("super") > QuarkTranscodePolicy.transcodeRank("high"))
        assertTrue(QuarkTranscodePolicy.transcodeRank("high") > QuarkTranscodePolicy.transcodeRank("normal"))
        assertTrue(QuarkTranscodePolicy.transcodeRank("normal") > QuarkTranscodePolicy.transcodeRank("low"))
        assertEquals(0, QuarkTranscodePolicy.transcodeRank("unknown"))
    }
}
