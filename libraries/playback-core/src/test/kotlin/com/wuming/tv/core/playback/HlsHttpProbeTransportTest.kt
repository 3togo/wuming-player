package com.wuming.tv.core.playback

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class HlsHttpProbeTransportTest {
    @Test fun boundsBodiesWhenServersIgnoreRangeAndPreservesRequestHeaders() {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val headers = mutableMapOf<String, String>()
        server.createContext("/") { exchange ->
            headers["Cookie"] = exchange.requestHeaders.getFirst("Cookie")
            headers["Range"] = exchange.requestHeaders.getFirst("Range")
            val bytes = ByteArray(128 * 1024) { 1 }
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            runCatching { exchange.responseBody.use { it.write(bytes) } }
            exchange.close()
        }
        server.start()
        try {
            HlsHttpProbeTransport(OkHttpClient()).use { transport ->
                val prefix = transport.readPrefix("http://127.0.0.1:${server.address.port}/segment.ts",
                    mapOf("Cookie" to "fixture", "Range" to "bytes=0-1023"), 1024, 3000)
                assertEquals(200, prefix.status)
                assertEquals(1024, prefix.bytes.size)
                assertEquals("fixture", headers["Cookie"])
                assertEquals("bytes=0-1023", headers["Range"])
            }
        } finally { server.stop(0) }
    }

    @Test fun closingTransportCancelsAnActiveHttpReadAndRejectsNewRequests() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            exchange.close()
        }
        server.start()
        val executor = Executors.newSingleThreadExecutor()
        val transport = HlsHttpProbeTransport(OkHttpClient())
        val url = "http://127.0.0.1:${server.address.port}/segment.ts"
        try {
            val future = executor.submit<HlsSegmentProbeSession.Prefix> { transport.readPrefix(url, emptyMap(), 1024, 3000) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            transport.close()
            val failure = assertThrows(ExecutionException::class.java) { future.get(3, TimeUnit.SECONDS) }
            assertTrue(failure.cause is IOException)
            assertThrows(IOException::class.java) { transport.readPrefix(url, emptyMap(), 1024, 3000) }
        } finally {
            release.countDown()
            transport.close()
            executor.shutdownNow()
            server.stop(0)
        }
    }
}
