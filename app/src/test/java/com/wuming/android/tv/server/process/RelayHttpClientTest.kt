package com.wuming.android.tv.server.process

import okhttp3.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayHttpClientTest {

    @Test
    fun clientIsSingleton() {
        val first = RelayHttpClient.client()
        val second = RelayHttpClient.client()
        assertNotNull(first)
        assertSame(first, second)
    }

    @Test
    fun clientHasExpectedTimeoutsAndProtocols() {
        val client = RelayHttpClient.client()
        assertEquals(10_000, client.connectTimeoutMillis)
        assertEquals(120_000, client.readTimeoutMillis)
        assertEquals(30_000, client.writeTimeoutMillis)
        assertEquals(0, client.callTimeoutMillis) // no call timeout
        assertTrue(client.protocols.contains(Protocol.HTTP_2))
        assertTrue(client.protocols.contains(Protocol.HTTP_1_1))
    }

    @Test
    fun upstreamCallBuildsGetRequestWithHeaders() {
        val call = RelayHttpClient.upstreamCall(
            "https://up.example.com/file",
            mapOf("X-Token" to "abc", "User-Agent" to "tvbox"),
            null,
            null,
        )
        val request = call.request()
        assertEquals("https://up.example.com/file", request.url.toString())
        assertEquals("GET", request.method)
        assertEquals("abc", request.header("X-Token"))
        assertEquals("tvbox", request.header("User-Agent"))
    }

    @Test
    fun upstreamCallHonoursHeadMethodAndRange() {
        val call = RelayHttpClient.upstreamCall(
            "https://up.example.com/file",
            emptyMap(),
            "HEAD",
            "bytes=0-99",
        )
        val request = call.request()
        assertEquals("HEAD", request.method)
        assertEquals("bytes=0-99", request.header("Range"))
    }

    @Test
    fun upstreamCallIgnoresInvalidHeaderButKeepsValidOnes() {
        val call = RelayHttpClient.upstreamCall(
            "https://up.example.com/file",
            mapOf("X-Valid" to "ok", "\r\nBad" to "drop"),
            null,
            null,
        )
        val request = call.request()
        assertEquals("ok", request.header("X-Valid"))
        assertEquals(null, request.header("\r\nBad"))
    }
}
