package com.wuming.tv.desktop.js

import com.wuming.tv.core.runtime.js.JsProxyResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URI

class DesktopJsProxyServerTest {

    @Test
    fun retiringRegistrationLeavesReplacementRoutesUsable() {
        DesktopJsProxyServer().use { server ->
            val old = server.register("same")
            val current = server.register("same")
            old.attach { _, _, _ -> JsProxyResponse(200, byteArrayOf(1), emptyMap()) }
            current.attach { _, _, _ -> JsProxyResponse(200, byteArrayOf(2), emptyMap()) }
            val oldUrl = old.create("stream/old", emptyMap())
            val currentUrl = current.create("stream/current", emptyMap())
            old.close()
            fun response(url: String): Int {
                val connection = URI(url).toURL().openConnection() as HttpURLConnection
                return try { connection.responseCode } finally { connection.disconnect() }
            }
            assertEquals(404, response(oldUrl))
            assertEquals(200, response(currentUrl))
        }
    }

    @Test
    fun servesOnlyMintedLoopbackCapabilitiesAndRevokesThemOnClose() {
        DesktopJsProxyServer().use { server ->
            val registration = server.register("provider")
            registration.attach { segments, headers, requestHeaders ->
                assertEquals(listOf("hls", "https%3A%2F%2Fmedia.example%2Fa%20b.m3u8"), segments)
                assertEquals("source", headers["X-Mode"])
                assertEquals("request", requestHeaders.entries.first { it.key.equals("X-Test", true) }.value)
                JsProxyResponse(206, "proxied".toByteArray(), mapOf("Content-Type" to "application/vnd.apple.mpegurl"))
            }
            val location = registration.create(
                "hls/https%3A%2F%2Fmedia.example%2Fa%20b.m3u8",
                mapOf("X-Mode" to "source"),
            )
            val connection = URI(location).toURL().openConnection() as HttpURLConnection
            connection.setRequestProperty("X-Test", "request")
            assertEquals(206, connection.responseCode)
            assertEquals("proxied", connection.inputStream.bufferedReader().readText())
            assertEquals("application/vnd.apple.mpegurl", connection.contentType)

            registration.close()
            assertEquals(404, (URI(location).toURL().openConnection() as HttpURLConnection).responseCode)
            assertThrows(IllegalStateException::class.java) {
                registration.create("hls/value", emptyMap())
            }
        }
    }
}
