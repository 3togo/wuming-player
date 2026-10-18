package com.wuming.tv.desktop.player

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class DesktopRedirectConnectionTest {
    @Test fun onlyTheResolverHopClosesAndRequestHeadersSurviveRedirects() {
        val seen = CopyOnWriteArrayList<Pair<String, String?>>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            val host = exchange.requestHeaders.getFirst("Host").substringBefore(':')
            seen.add(host to exchange.requestHeaders.getFirst("Connection"))
            assertEquals("bytes=0-511", exchange.requestHeaders.getFirst("Range"))
            assertEquals("Fixture-UA", exchange.requestHeaders.getFirst("User-Agent"))
            when (exchange.requestURI.path) {
                "/entry" -> {
                    exchange.responseHeaders.add("Location", "http://gslbmgsplive.miguvideo.com:${server.address.port}/resolve")
                    exchange.sendResponseHeaders(302, -1)
                }
                "/resolve" -> {
                    exchange.responseHeaders.add("Location", "http://cdn.example:${server.address.port}/playlist")
                    exchange.sendResponseHeaders(302, -1)
                }
                else -> {
                    val body = "#EXTM3U\n".toByteArray()
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.write(body)
                }
            }
            exchange.close()
        }
        server.start()
        val client = DesktopPlaybackHttpClient.client.newBuilder().proxy(Proxy.NO_PROXY)
            .dns { listOf(InetAddress.getLoopbackAddress()) }.build()
        try {
            client.newCall(Request.Builder().url("http://entry.example:${server.address.port}/entry")
                .header("Range", "bytes=0-511").header("User-Agent", "Fixture-UA").build())
                .execute().use { response ->
                    assertEquals(200, response.code)
                    assertEquals("#EXTM3U\n", response.body.string())
                }
            assertEquals(listOf("entry.example", "gslbmgsplive.miguvideo.com", "cdn.example"), seen.map { it.first })
            assertFalse(seen[0].second.equals("close", true))
            assertEquals("close", seen[1].second)
            assertFalse(seen[2].second.equals("close", true))
        } finally {
            server.stop(0)
            client.connectionPool.evictAll()
        }
    }
}
