package com.wuming.tv.core.hongguo

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class HongGuoHttpTransportTest {
    @Test fun followsOnlyAllowedRedirectsAndRejectsHttpErrorsAndOversizedBodies() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                when (exchange.requestURI.path) {
                    "/redirect" -> { exchange.responseHeaders.add("Location", "/page"); exchange.sendResponseHeaders(302, -1) }
                    "/foreign" -> { exchange.responseHeaders.add("Location", "http://foreign.invalid/page"); exchange.sendResponseHeaders(302, -1) }
                    "/error" -> exchange.sendResponseHeaders(503, -1)
                    else -> {
                        val bytes = if (exchange.requestURI.path == "/large") ByteArray(2 * 1024 * 1024 + 1) else "真实页面".toByteArray()
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                        exchange.responseBody.write(bytes)
                    }
                }
            } finally { exchange.close() }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val transport = HongGuoHttpTransport(OkHttpClient()) { it.startsWith("$base/") }
            assertEquals("真实页面", transport.get("$base/redirect", emptyMap()))
            assertThrows(IllegalArgumentException::class.java) { transport.get("$base/foreign", emptyMap()) }
            assertThrows(IllegalArgumentException::class.java) { transport.get("$base/error", emptyMap()) }
            assertThrows(IllegalArgumentException::class.java) { transport.get("$base/large", emptyMap()) }
        } finally { server.stop(0) }
    }

    @Test fun cancellationReleasesOnlyThisAdaptersCallAndRetainsTheSuppliedClient() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val serverPool = Executors.newCachedThreadPool()
        server.executor = serverPool
        val waiting = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.createContext("/") { exchange ->
            try {
                exchange.sendResponseHeaders(200, 2)
                if (exchange.requestURI.path == "/wait") {
                    waiting.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
                runCatching { exchange.responseBody.write("ok".toByteArray()) }
            } finally { exchange.close() }
        }
        server.start()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val client = OkHttpClient()
            val transport = HongGuoHttpTransport(client) { it.startsWith("$base/") }
            val result = worker.submit<String> { transport.get("$base/wait", emptyMap()) }
            assertTrue(waiting.await(5, TimeUnit.SECONDS))
            transport.cancel()
            assertTrue(assertThrows(ExecutionException::class.java) { result.get(5, TimeUnit.SECONDS) }.cause is java.io.IOException)
            client.newCall(Request.Builder().url("$base/ok").build()).execute().use { response ->
                assertEquals("ok", response.body.string())
            }
        } finally {
            release.countDown()
            worker.shutdownNow()
            server.stop(0)
            serverPool.shutdownNow()
        }
    }
}
