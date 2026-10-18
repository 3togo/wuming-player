package com.wuming.tv.desktop.cache

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResourceHttpCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun realTransportHandles304AndLastModified() {
        var requests = 0
        val modified = "Wed, 21 Oct 2015 07:28:00 GMT"
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/feed") { exchange ->
                requests++
                exchange.responseHeaders.add("Cache-Control", "max-age=0")
                exchange.responseHeaders.add("Last-Modified", modified)
                if (exchange.requestHeaders.getFirst("If-Modified-Since") == modified) {
                    exchange.sendResponseHeaders(304, -1)
                    exchange.close()
                } else {
                    val body = "payload".toByteArray()
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
            }
            start()
        }
        try {
            val cache = ResourceDiskCache(temporary.root.toPath(), Duration.ofHours(1), Duration.ofDays(1), 2, 8192, 1024)
            val url = "http://127.0.0.1:${server.address.port}/feed"
            assertEquals(ResourceOrigin.NETWORK, cache.load(url) { true }.origin)
            val result = cache.load(url) { true }
            assertEquals(ResourceOrigin.REVALIDATED, result.origin)
            assertEquals("payload", result.bytes.toString(Charsets.UTF_8))
            assertEquals(2, requests)
        } finally { server.stop(0) }
    }
}
