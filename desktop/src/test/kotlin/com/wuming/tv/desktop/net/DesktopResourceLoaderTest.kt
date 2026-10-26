package com.wuming.tv.desktop.net

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopResourceLoaderTest {

    @Test
    fun cancelledRequestsStopBeforeNetworkAccessAndKeepTheInterrupt() {
        try {
            Thread.currentThread().interrupt()
            assertThrows(InterruptedException::class.java) {
                DesktopResourceLoader().load("http://127.0.0.1:1/cancelled")
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun loadsBundledLeanbackAsset() {
        val content = DesktopResourceLoader().load("assets://live/public_samples.m3u8").toString(Charsets.UTF_8)

        assertTrue(content.contains("#EXTM3U"))
    }

    @Test
    fun convertsInternationalHostNamesForTheHttpClient() {
        val uri = DesktopResourceLoader().normalizeHttpUri("http://www.饭太硬.cc/tv")

        assertEquals("www.xn--sss604efuw.cc", uri.host)
        assertEquals("/tv", uri.path)
    }

    @Test
    fun includesBoundedServerExplanationInHttpFailure() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/denied") { exchange ->
                val body = "the source ip is forbidden\n".toByteArray()
                exchange.sendResponseHeaders(403, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                DesktopResourceLoader().load("http://127.0.0.1:${server.address.port}/denied")
            }

            assertTrue(failure.message.orEmpty().contains("HTTP 403"))
            assertTrue(failure.message.orEmpty().contains("source ip is forbidden"))
        } finally {
            server.stop(0)
        }
    }
}
