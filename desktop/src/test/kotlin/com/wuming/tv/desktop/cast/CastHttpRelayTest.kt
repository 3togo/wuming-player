package com.wuming.tv.desktop.cast

import com.sun.net.httpserver.HttpServer
import com.wuming.tv.core.playback.BilibiliCastPlayback
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicInteger

class CastHttpRelayTest {
    @Test fun forwardsExactRangesAndBrowserContextWithoutLeakingCredentialsOrHeadBodies() {
        val requests = AtomicInteger()
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val bytes = "fixture8".toByteArray()
        upstream.createContext("/video.mp4") { exchange ->
            try {
                assertEquals("bytes=123-130", exchange.requestHeaders.getFirst("Range"))
                assertEquals("https://www.bilibili.com/", exchange.requestHeaders.getFirst("Referer"))
                assertEquals("Mozilla/5.0", exchange.requestHeaders.getFirst("User-Agent"))
                assertNull(exchange.requestHeaders.getFirst("Cookie"))
                assertNull(exchange.requestHeaders.getFirst("Authorization"))
                requests.incrementAndGet()
                exchange.responseHeaders.set("Content-Type", "video/mp4")
                exchange.responseHeaders.set("Content-Range", "bytes 123-130/9999")
                exchange.responseHeaders.set("Content-Length", bytes.size.toString())
                val head = exchange.requestMethod == "HEAD"
                exchange.sendResponseHeaders(206, if (head) -1 else bytes.size.toLong())
                if (!head) exchange.responseBody.write(bytes)
            } finally { exchange.close() }
        }
        upstream.start()
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()
        try {
            CastHttpRelay("http://127.0.0.1:${upstream.address.port}/video.mp4", BilibiliCastPlayback.headers,
                "127.0.0.1", client).use { relay ->
                for (method in listOf("GET", "HEAD", "GET")) {
                    client.newCall(Request.Builder().url(relay.url).method(method, null).header("Range", "bytes=123-130").build())
                        .execute().use { response ->
                            assertEquals(206, response.code)
                            assertEquals("bytes 123-130/9999", response.header("Content-Range"))
                            assertEquals("8", response.header("Content-Length"))
                            assertArrayEquals(if (method == "HEAD") byteArrayOf() else bytes, response.body.bytes())
                        }
                }
                client.newCall(Request.Builder().url(relay.url + "/forged").build()).execute().use { assertEquals(404, it.code) }
                assertEquals(3, requests.get())
            }
        } finally {
            upstream.stop(0)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
    @Test fun mediaTransferOutlivesApiCallBudget() {
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstream.createContext("/") { exchange ->
            try {
                exchange.sendResponseHeaders(200, 2)
                exchange.responseBody.write('a'.code)
                exchange.responseBody.flush()
                Thread.sleep(300)
                exchange.responseBody.write('b'.code)
            } finally { exchange.close() }
        }
        upstream.start()
        val apiClient = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
            .callTimeout(java.time.Duration.ofMillis(100)).build()
        val receiverClient = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()
        try {
            CastHttpRelay("http://127.0.0.1:${upstream.address.port}/", emptyMap(), "127.0.0.1", apiClient).use { relay ->
                receiverClient.newCall(Request.Builder().url(relay.url).build()).execute().use {
                    assertEquals("ab", it.body.string())
                }
            }
        } finally {
            upstream.stop(0)
            for (client in listOf(apiClient, receiverClient)) {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }

}
