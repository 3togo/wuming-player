package com.wuming.tv.desktop.live

import com.wuming.tv.core.live.LiveLineSelectionPolicy
import com.wuming.tv.core.live.LiveStream
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DesktopLiveStreamProbeTest {
    private lateinit var server: HttpServer
    private lateinit var base: String
    private val hitCounts = ConcurrentHashMap<String, AtomicInteger>()

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/empty") { exchange ->
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.createContext("/stale") { exchange ->
            record(exchange.requestURI.path)
            exchange.sendResponseHeaders(403, -1)
            exchange.close()
        }
        server.createContext("/live") { exchange ->
            record(exchange.requestURI.path)
            if (exchange.requestHeaders.getFirst("User-Agent") == "Playlist-UA") {
                respond(exchange)
            } else {
                exchange.sendResponseHeaders(403, -1)
                exchange.close()
            }
        }
        server.createContext("/fast") { exchange ->
            record(exchange.requestURI.path)
            respond(exchange)
        }
        server.createContext("/slow") { exchange ->
            record(exchange.requestURI.path)
            Thread.sleep(120)
            respond(exchange)
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun stop() = server.stop(0)

    @Test
    fun `rejects stale streams and forwards playlist headers`() {
        val probe = DesktopLiveStreamProbe(requestTimeout = Duration.ofSeconds(2))

        assertFalse(probe.reachable(LiveStream("$base/stale")))
        assertTrue(probe.reachable(LiveStream("$base/live", mapOf("User-Agent" to "Playlist-UA"))))
    }

    @Test
    fun `empty successful response falls through to an alternate with data`() {
        val probe = DesktopLiveStreamProbe(requestTimeout = Duration.ofSeconds(2))
        val empty = LiveStream("$base/empty")
        val live = LiveStream("$base/fast")
        assertFalse(probe.reachable(empty))
        assertEquals(live, probe.selectStream(listOf(empty, live)))
    }

    @Test
    fun `selected channel falls through stale alternate routes`() {
        val stale = LiveStream("$base/stale")
        val live = LiveStream("$base/live", mapOf("User-Agent" to "Playlist-UA"))
        val probe = DesktopLiveStreamProbe(requestTimeout = Duration.ofSeconds(2))

        assertEquals(live, probe.selectStream(listOf(stale, live)))
    }

    @Test
    fun `answers rank lines by latency and spare the ones already known bad`() {
        val probe = DesktopLiveStreamProbe(requestTimeout = Duration.ofSeconds(2))
        val stale = LiveStream("$base/stale")
        val slow = LiveStream("$base/slow")
        val fast = LiveStream("$base/fast")
        probe.reachable(stale)
        probe.reachable(slow)
        probe.reachable(fast)
        val beforeStale = hits("/stale")
        val beforeSlow = hits("/slow")
        val beforeFast = hits("/fast")

        assertEquals(fast, probe.selectStream(listOf(stale, slow, fast), preferredIndex = 0))

        assertEquals(beforeFast + 1, hits("/fast"))
        assertEquals(beforeStale, hits("/stale"))
        assertEquals(beforeSlow, hits("/slow"))
    }

    @Test
    fun `known bad lines are re-attempted only after the cooldown`() {
        var now = 0L
        val probe = DesktopLiveStreamProbe(requestTimeout = Duration.ofSeconds(2), clock = { now })
        val stale = LiveStream("$base/stale")
        val live = LiveStream("$base/live", mapOf("User-Agent" to "Playlist-UA"))

        assertEquals(live, probe.selectStream(listOf(stale, live)))
        val afterFirstAttempt = hits("/stale")

        now = LiveLineSelectionPolicy.REPROBE_AFTER_MILLIS - 1
        assertEquals(live, probe.selectStream(listOf(stale, live)))
        assertEquals(afterFirstAttempt, hits("/stale"))

        now = LiveLineSelectionPolicy.REPROBE_AFTER_MILLIS
        assertEquals(live, probe.selectStream(listOf(stale, live)))
        assertTrue(hits("/stale") > afterFirstAttempt)
    }

    private fun hits(path: String): Int = hitCounts[path]?.get() ?: 0

    private fun record(path: String) = hitCounts.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()

    private fun respond(exchange: HttpExchange) {
        exchange.sendResponseHeaders(206, 1)
        exchange.responseBody.use { it.write(byteArrayOf(1)) }
    }
}
