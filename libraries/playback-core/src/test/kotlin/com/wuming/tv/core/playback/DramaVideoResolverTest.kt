package com.wuming.tv.core.playback

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Request
import okio.Buffer
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Random
import java.util.zip.GZIPInputStream
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
    private val registration = """{"device_id_str":"101","install_id_str":"202","klink_egdi":"fixture"}"""
    private fun currentResponse(objectModel: Boolean = false): String {
        val old = JsonParser.parseString(response(rendition(1080))).asJsonObject
        val model = old.getAsJsonObject("data").getAsJsonObject("video_model_data").getAsJsonObject("22").get("video_model")
        val data = JsonObject().apply { add("video_model", if (objectModel) JsonParser.parseString(model.asString) else model) }
        return JsonObject().apply { addProperty("code", 0); add("data", data) }.toString()
    }
    private fun service(exchange: (Request) -> String) = DramaServiceMetadata(exchange, { 1700000000000L }, Random(7))

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
        for (objectModel in listOf(false, true)) {
            val reference = DramaVideoResolver { currentResponse(objectModel) }.resolve("11/22")
            assertEquals("http://media.example/video-1080.mp4?signature=fixture", CencPlaybackSessions.require(reference).url)
            assertEquals("000102030405060708090a0b0c0d0e0f", CencPlaybackSessions.require(reference).keyHex())
        }
    }

    @Test fun signedNativeMetadataCreatesTheExpectedPlaybackSession() {
        val requests = mutableListOf<Request>()
        val metadata = service { request ->
            requests += request
            if (requests.size == 1) registration else currentResponse()
        }
        val reference = DramaVideoResolver(metadata::fetch).resolve("11/22")

        assertEquals(2, requests.size)
        assertEquals("/service/2/device_register/", requests[0].url.encodedPath)
        val playback = requests[1]
        assertEquals("POST", playback.method)
        assertEquals("api5-normal-sinfonlinea.fqnovel.com", playback.url.host)
        assertEquals("/novel/player/video_model/v1/", playback.url.encodedPath)
        assertEquals("101", playback.url.queryParameter("device_id"))
        assertEquals("202", playback.url.queryParameter("iid"))
        assertEquals("gzip", playback.header("Content-Encoding"))
        val compressed = Buffer().also { playback.body!!.writeTo(it) }.readByteArray()
        val payload = GZIPInputStream(ByteArrayInputStream(compressed)).bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
        assertEquals("22", payload.get("video_id").asString)
        assertEquals(1004, payload.get("content_type").asInt)
        assertTrue(playback.header("x-medusa")!!.isNotBlank())
        assertTrue(playback.header("x-helios")!!.isNotBlank())
        assertNull(playback.header("Cookie"))
        assertTrue(CencPlaybackSessions.matches(reference))
        assertEquals("http://media.example/video-1080.mp4?signature=fixture", CencPlaybackSessions.require(reference).url)
        assertEquals("000102030405060708090a0b0c0d0e0f", CencPlaybackSessions.require(reference).keyHex())
    }

    @Test fun emptyNativeMetadataCanBeRetriedWithoutRegisteringAgain() {
        val requests = mutableListOf<Request>()
        val metadata = service { request ->
            requests += request
            when (requests.size) { 1 -> registration; 2 -> ""; else -> currentResponse() }
        }
        val resolver = DramaVideoResolver(metadata::fetch)
        val failure = assertThrows(IOException::class.java) { resolver.resolve("11/22") }
        assertEquals("The drama service returned an empty response. Try again later.", failure.message)
        assertNull(failure.cause)

        val reference = resolver.resolve("11/22")
        assertEquals(3, requests.size)
        assertEquals(requests[1].url.encodedPath, requests[2].url.encodedPath)
        assertEquals(requests[1].url.queryParameter("device_id"), requests[2].url.queryParameter("device_id"))
        assertEquals(requests[1].url.queryParameter("cdid"), requests[2].url.queryParameter("cdid"))
        assertEquals("http://media.example/video-1080.mp4?signature=fixture", CencPlaybackSessions.require(reference).url)
        assertEquals("000102030405060708090a0b0c0d0e0f", CencPlaybackSessions.require(reference).keyHex())
    }

    @Test fun registrationTransportFailureDoesNotPreventTheNextPlaybackAttempt() {
        val requests = mutableListOf<Request>()
        val metadata = service { request ->
            requests += request
            when (requests.size) { 1 -> throw IOException("Fixture transport failure"); 2 -> registration; else -> currentResponse(true) }
        }
        val resolver = DramaVideoResolver(metadata::fetch)
        assertThrows(IOException::class.java) { resolver.resolve("11/22") }
        assertEquals(1, requests.size)
        assertEquals("/service/2/device_register/", requests.single().url.encodedPath)

        val reference = resolver.resolve("11/22")
        assertEquals(3, requests.size)
        assertEquals("/service/2/device_register/", requests[1].url.encodedPath)
        assertEquals("/novel/player/video_model/v1/", requests[2].url.encodedPath)
        assertEquals("000102030405060708090a0b0c0d0e0f", CencPlaybackSessions.require(reference).keyHex())
    }
}
