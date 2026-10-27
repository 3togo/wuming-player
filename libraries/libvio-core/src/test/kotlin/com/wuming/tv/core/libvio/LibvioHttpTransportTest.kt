package com.wuming.tv.core.libvio

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

/** Real loopback HTTP exercises redirects, session isolation, bounds and socket cancellation offline. */
class LibvioHttpTransportTest {
    private fun server(check: (HttpServer, String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val workers = Executors.newCachedThreadPool()
        server.executor = workers
        server.start()
        try { check(server, "http://127.0.0.1:${server.address.port}/") }
        finally { server.stop(0); workers.shutdownNow() }
    }
    private fun transport(base: String) = LibvioHttpTransport(OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()) { it.startsWith(base) }

    @Test fun followsSessionRedirectsAndKeepsCookiesSeparateFromOtherSources() = server { server, base ->
        server.createContext("/start") { exchange ->
            exchange.responseHeaders.add("Set-Cookie", "clearance=ready; Path=/")
            exchange.responseHeaders.add("Location", "/done")
            exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        server.createContext("/done") { exchange ->
            val value = exchange.requestHeaders.getFirst("Cookie").orEmpty().toByteArray()
            exchange.sendResponseHeaders(200, value.size.toLong()); exchange.responseBody.use { it.write(value) }
        }
        val first = transport(base)
        first.setCookie(base, "__cdn_pow=proof")
        val result = first.get(base + "start", emptyMap())
        assertTrue(result.contains("clearance=ready"))
        assertTrue(result.contains("__cdn_pow=proof"))
        assertEquals("", transport(base).get(base + "done", emptyMap()))
    }
    @Test fun rejectsRedirectBeforeContactingAnUnrelatedOrigin() = server { server, base ->
        server.createContext("/") { exchange ->
            exchange.responseHeaders.add("Location", "http://unrelated.invalid/private")
            exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        assertThrows(IllegalArgumentException::class.java) { transport(base).get(base, emptyMap()) }
    }
    @Test fun boundsRedirectLoops() = server { server, base ->
        val count = AtomicInteger()
        server.createContext("/") { exchange ->
            count.incrementAndGet(); exchange.responseHeaders.add("Location", "/")
            exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        assertThrows(IllegalStateException::class.java) { transport(base).get(base, emptyMap()) }
        assertEquals(6, count.get())
    }
    @Test fun boundsLargeResponseBodies() = server { server, base ->
        server.createContext("/") { exchange ->
            val body = ByteArray(2 * 1024 * 1024 + 1)
            exchange.sendResponseHeaders(200, body.size.toLong())
            runCatching { exchange.responseBody.use { it.write(body) } }
        }
        assertThrows(IllegalArgumentException::class.java) { transport(base).get(base, emptyMap()) }
    }
    @Test fun acceptsOnlyRecognizedChallengeBodiesOnHttpFailure() = server { server, base ->
        server.createContext("/") { exchange ->
            val body = if (exchange.requestURI.path == "/challenge") "var POW = \"__cdn_pow\";" else "denied"
            exchange.sendResponseHeaders(403, body.length.toLong()); exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        assertEquals("var POW = \"__cdn_pow\";", transport(base).get(base + "challenge", emptyMap()))
        assertThrows(IllegalArgumentException::class.java) { transport(base).get(base, emptyMap()) }
    }
    @Test fun cancelsPendingSocketRead() = server { server, base ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        server.createContext("/") { exchange ->
            entered.countDown(); release.await(3, TimeUnit.SECONDS)
            runCatching { exchange.sendResponseHeaders(200, -1); exchange.close() }
        }
        try {
            val client = transport(base)
            val future = worker.submit<String> { client.get(base, emptyMap()) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            client.cancel()
            val failure = assertThrows(ExecutionException::class.java) { future.get(2, TimeUnit.SECONDS) }
            assertTrue(failure.cause is java.io.IOException)
        } finally { release.countDown(); worker.shutdownNow() }
    }
    @Test fun productionPolicyRejectsLoopbackWithoutSendingARequest() = server { server, base ->
        val calls = AtomicInteger()
        server.createContext("/") { exchange -> calls.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close() }
        assertThrows(IllegalArgumentException::class.java) { LibvioHttpTransport(OkHttpClient()).get(base, emptyMap()) }
        assertEquals(0, calls.get())
    }
}
