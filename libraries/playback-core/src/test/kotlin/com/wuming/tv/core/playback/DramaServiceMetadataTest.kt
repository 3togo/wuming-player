package com.wuming.tv.core.playback

import com.google.gson.JsonParser
import com.wuming.tv.core.base.Hex
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Random
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class DramaServiceMetadataTest {
    private val registration = """{"device_id_str":"101","install_id_str":"202","klink_egdi":"fixture"}"""
    private fun body(request: Request): ByteArray = Buffer().also { request.body!!.writeTo(it) }.readByteArray()

    @Test fun registersOnceThenSignsTheExactCompressedEpisodeRequest() {
        val requests = mutableListOf<Request>()
        val service = DramaServiceMetadata({ request ->
            requests += request
            if (request.url.encodedPath.contains("device_register")) registration else "fixture metadata"
        }, { 1700000000000L }, Random(7))
        assertEquals("fixture metadata", service.fetch("22"))
        assertEquals("fixture metadata", service.fetch("23"))
        assertEquals(3, requests.size)
        val register = requests.first()
        assertEquals("POST", register.method)
        assertEquals("log5-applog-lf.fqnovel.com", register.url.host)
        val encoded = body(register)
        assertArrayEquals(byteArrayOf(116, 99, 5, 16, 0, 0), encoded.copyOf(6))
        val nonce = encoded.copyOfRange(6, 38)
        val key = DramaWire.digest("SHA-512", DramaWire.digest("SHA-512", nonce) + DramaWire.table("registrationSalt").map(Int::toByte).toByteArray())
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, 0, 16, "AES"), IvParameterSpec(key, 16, 16))
        val decoded = cipher.doFinal(encoded.copyOfRange(38, encoded.size))
        val compressed = decoded.copyOfRange(64, decoded.size)
        assertArrayEquals(DramaWire.digest("SHA-512", compressed), decoded.copyOf(64))
        val profile = GZIPInputStream(ByteArrayInputStream(compressed)).bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
        assertEquals("ss_app_log", profile.get("magic_tag").asString)
        assertEquals(8662, profile.getAsJsonObject("header").get("aid").asInt)
        for ((index, request) in requests.drop(1).withIndex()) {
            assertEquals("POST", request.method)
            assertEquals("/novel/player/video_model/v1/", request.url.encodedPath)
            assertEquals("101", request.url.queryParameter("device_id"))
            assertEquals("202", request.url.queryParameter("iid"))
            assertNull(request.header("Cookie"))
            assertEquals("gzip", request.header("Content-Encoding"))
            assertEquals(Hex.encodeUpper(DramaWire.digest("MD5", body(request))), request.header("x-ss-stub"))
            assertTrue(request.header("x-medusa")!!.isNotBlank())
            val payload = GZIPInputStream(ByteArrayInputStream(body(request))).bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
            assertEquals((22 + index).toString(), payload.get("video_id").asString)
            assertEquals(1004, payload.get("content_type").asInt)
            assertTrue(payload.getAsJsonObject("biz_param").get("need_all_video_definition").asBoolean)
        }
        assertEquals(requests[1].url.queryParameter("cdid"), requests[2].url.queryParameter("cdid"))
    }

    @Test fun aFailedRegistrationIsNotCachedOrFollowedByAMetadataRequest() {
        var attempts = 0
        var metadata = 0
        val service = DramaServiceMetadata({ request ->
            if (request.url.encodedPath.contains("device_register")) {
                attempts++; if (attempts == 1) """{"device_id_str":"0","install_id_str":"202","klink_egdi":"secret-fixture"}""" else registration
            } else { metadata++; "fixture" }
        }, { 1700000000000L }, Random(7))
        val failure = assertThrows(IOException::class.java) { service.fetch("22") }
        assertFalse(failure.message!!.contains("secret-fixture"))
        assertNull(failure.cause)
        assertEquals(0, metadata)
        assertEquals("fixture", service.fetch("22"))
        assertEquals(2, attempts)
        assertEquals(1, metadata)
    }
}
