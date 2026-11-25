package com.wuming.tv.core.playback

import com.google.gson.Gson
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class DramaVideoResolverTest {
    private val envelope = "mbwT+lS9E/RbvRz2VLoc9FW5EvZaphLfW6YT3VqhE99Zo9ZhYmNkZWY="
    private fun rendition(height: Int, wrapped: String = envelope) = mapOf(
        "main_url" to "http://media.example/video-$height.mp4?signature=fixture",
        "video_meta" to mapOf("codec_type" to "h264", "vheight" to height),
        "encrypt_info" to mapOf("encryption_method" to "cenc-aes-ctr", "kid" to "101112131415161718191a1b1c1d1e1f", "spade_a" to wrapped),
    )
    private fun response(vararg renditions: Map<String, Any>) = Gson().toJson(mapOf(
        "code" to 0, "data" to mapOf("video_model_data" to mapOf("22" to mapOf(
            "video_model" to Gson().toJson(mapOf("video_list" to renditions.toList())),
        ))),
    ))

    @Test fun metadataCreatesAnOpaqueSessionWithTheExpectedKey() {
        var requests = 0
        val resolver = DramaVideoResolver {
            requests++
            assertEquals("22", it)
            response(rendition(360), rendition(1080))
        }
        val reference = resolver.resolve("11/22")
        assertEquals(1, requests)
        assertTrue(CencPlaybackSessions.matches(reference))
        assertFalse(reference.contains("signature"))
        val material = CencPlaybackSessions.require(reference)
        assertEquals("http://media.example/video-1080.mp4?signature=fixture", material.url)
        assertEquals("000102030405060708090a0b0c0d0e0f", material.keyHex())
        assertEquals("CencPlaybackMaterial", material.toString())
    }

    @Test fun invalidHigherRenditionDoesNotHideAUsableLowerRendition() {
        val resolver = DramaVideoResolver { response(rendition(1080, "broken"), rendition(360)) }
        assertTrue(CencPlaybackSessions.require(resolver.resolve("11/22")).url.contains("360"))
    }

    @Test fun unknownEpisodesAndMalformedMetadataDoNotProduceAPlaybackSession() {
        var called = false
        val resolver = DramaVideoResolver { called = true; "{}" }
        for (id in listOf("", "11", "11/22/33", "https://example/video", "../22", "1/")) {
            assertThrows(IllegalArgumentException::class.java) { resolver.resolve(id) }
        }
        assertFalse(called)
        assertFalse(DramaVideoResolver.supports("csp_Other", "11/22"))
        assertFalse(DramaVideoResolver.supports("csp_AiDj", "https://example/video"))
        assertTrue(DramaVideoResolver.supports("csp_AiDj", "11/22"))
        assertTrue(DramaVideoResolver.supports("csp_AiDjGuard", "11/22"))
        assertTrue(DramaVideoResolver.supports("AiDjGuard", "11/22"))
        assertFalse(DramaVideoResolver.supports("csp_OtherGuard", "11/22"))
        assertThrows(IOException::class.java) { resolver.resolve("11/22") }
        for (text in listOf("", "abcd", "x".repeat(1025), "!!!!", "AAAA")) {
            assertThrows(IllegalArgumentException::class.java) { SpadeMetadata.key(text) }
        }
        assertThrows(IllegalStateException::class.java) { CencPlaybackSessions.require("wuming-cenc://missing") }
    }

    @Test fun emptyServiceResponsesExplainTheFailureWithoutLeakingParserDetails() {
        for (body in listOf("", " ", "\r\n\t")) {
            val failure = assertThrows(IOException::class.java) { DramaVideoResolver { body }.resolve("11/22") }
            assertEquals("The drama service returned an empty response. Try again later.", failure.message)
            assertNull(failure.cause)
        }
    }

    @Test fun malformedMetadataNeverExposesProviderContentInTheError() {
        val bodies = listOf("null", "[]", "true", "\"signed-secret\"", "{signed-secret",
            "{\"code\":0,\"data\":null}", "{\"code\":0,\"data\":[]}",
            "{\"code\":0,\"data\":{\"video_model_data\":{\"22\":{\"video_model\":\"null\"}}}}")
        for (body in bodies) {
            val failure = assertThrows(IOException::class.java) { DramaVideoResolver { body }.resolve("11/22") }
            assertFalse(failure.message.orEmpty().contains("signed-secret"))
            assertFalse(failure.message.orEmpty().contains("Not a JSON"))
            assertNull(failure.cause)
        }
    }

    @Test fun invalidRenditionMetadataDoesNotHideAPlayableRendition() {
        val invalid = rendition(1080).toMutableMap().apply { put("video_meta", "invalid") }
        val resolver = DramaVideoResolver { response(invalid, rendition(360)) }
        assertTrue(CencPlaybackSessions.require(resolver.resolve("11/22")).url.contains("360"))
    }

    @Test fun currentSingleVideoResponsesAcceptStringAndObjectModels() {
        val old = com.google.gson.JsonParser.parseString(response(rendition(1080))).asJsonObject
        val model = old.getAsJsonObject("data").getAsJsonObject("video_model_data").getAsJsonObject("22").get("video_model")
        for (value in listOf(model, com.google.gson.JsonParser.parseString(model.asString))) {
            val data = com.google.gson.JsonObject().apply { add("video_model", value) }
            val current = com.google.gson.JsonObject().apply { addProperty("code", 0); add("data", data) }
            val reference = DramaVideoResolver { current.toString() }.resolve("11/22")
            assertEquals("http://media.example/video-1080.mp4?signature=fixture", CencPlaybackSessions.require(reference).url)
            assertEquals("000102030405060708090a0b0c0d0e0f", CencPlaybackSessions.require(reference).keyHex())
        }
    }
}
