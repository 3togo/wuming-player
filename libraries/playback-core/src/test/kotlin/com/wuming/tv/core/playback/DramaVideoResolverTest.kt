package com.wuming.tv.core.playback

import com.google.gson.Gson
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
            assertTrue(it.startsWith("https://api5-normal-sinfonlineb.fqnovel.com/"))
            assertTrue(it.contains("video_id=22"))
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
        assertThrows(IllegalArgumentException::class.java) { resolver.resolve("11/22") }
        for (text in listOf("", "abcd", "x".repeat(1025), "!!!!", "AAAA")) {
            assertThrows(IllegalArgumentException::class.java) { SpadeMetadata.key(text) }
        }
        assertThrows(IllegalStateException::class.java) { CencPlaybackSessions.require("wuming-cenc://missing") }
    }
}
