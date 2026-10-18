package com.wuming.tv.desktop.server

import com.google.gson.Gson
import com.sun.net.httpserver.HttpServer
import com.wuming.tv.core.playback.relay.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.file.Files

class DesktopLanRelayTest {
    @Test fun stoppingDuringPreparationDoesNotRepopulateMonitorSessions() {
        withFixture { upstream, relay, client, token ->
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            upstream.createContext("/") { exchange ->
                entered.countDown()
                release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                runCatching {
                    val bytes = "#EXTM3U\n#EXTINF:2,\nsegment.ts\n".toByteArray()
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                exchange.close()
            }
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                val request = executor.submit {
                    runCatching { sessionResponse(relay, client, token,
                        "http://upstream.example:${upstream.address.port}/index.m3u8").use { it.body!!.string() } }
                }
                assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(1, relay.monitorSnapshot().preparing)
                relay.close()
                release.countDown()
                request.get(5, java.util.concurrent.TimeUnit.SECONDS)
                val snapshot = awaitIdleMonitor(relay)
                assertFalse(snapshot.enabled)
                assertTrue("Late preparation cannot recreate a stopped relay session", snapshot.sessions.isEmpty())
            } finally { release.countDown(); executor.shutdownNow() }
        }
    }

    @Test fun discoveryRepliesUseTheHttpBindingAddress() {
        val directory = Files.createTempDirectory("relay-discovery-binding-test")
        val relay = DesktopLanRelay(DesktopLanRelaySettings(directory.resolve("settings")))
        val bind = InetAddress.getByName("127.0.0.2")
        try {
            relay.enable(bind)
            java.net.DatagramSocket().use { socket ->
                socket.soTimeout = 2000
                val nonce = LanRelayProtocol.secret()
                val bytes = "${LanRelayProtocol.DISCOVER}|$nonce|client-123".toByteArray()
                socket.send(java.net.DatagramPacket(bytes, bytes.size, InetAddress.getByName("127.0.0.1"), LanRelayProtocol.DISCOVERY_PORT))
                val response = java.net.DatagramPacket(ByteArray(2048), 2048)
                socket.receive(response)
                assertEquals("Discovery must advertise the address actually serving HTTP", bind, response.address)
                val advertised = Gson().fromJson(String(response.data, 0, response.length), RelayAdvertisement::class.java)
                assertEquals(java.net.URI(relay.address).port, advertised.port)
                assertEquals(nonce, advertised.nonce)
                OkHttpClient().newCall(Request.Builder().url("http://${response.address.hostAddress}:${advertised.port}${LanRelayProtocol.ROOT}/session")
                    .post("{}".toRequestBody("application/json".toMediaType())).build()).execute().use { assertEquals(401, it.code) }
            }
        } finally {
            relay.disable()
            Files.walk(directory).sorted(Comparator.reverseOrder()).use { it.forEach(Files::delete) }
        }
    }

    @Test fun preparedRedirectedPlaylistKeepsItsFinalBaseAndIsConsumedOnce() {
        val reads = java.util.concurrent.atomic.AtomicInteger()
        withFixture { upstream, relay, client, token ->
            upstream.createContext("/") { exchange ->
                when (exchange.requestURI.path) {
                    "/redirect.m3u8" -> {
                        exchange.responseHeaders.set("Location", "/nested/index.m3u8")
                        exchange.sendResponseHeaders(302, -1)
                    }
                    "/nested/index.m3u8" -> {
                        reads.incrementAndGet()
                        val bytes = "#EXTM3U\n#EXTINF:2,\nsegment.ts\n".toByteArray()
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                        exchange.responseBody.use { it.write(bytes) }
                    }
                    "/nested/segment.ts" -> {
                        exchange.sendResponseHeaders(200, 3)
                        exchange.responseBody.use { it.write(byteArrayOf(1, 2, 3)) }
                    }
                    else -> exchange.sendResponseHeaders(404, -1)
                }
                exchange.close()
            }
            val url = createSession(relay, client, token, "http://upstream.example:${upstream.address.port}/redirect.m3u8")
            assertEquals(0, awaitIdleMonitor(relay).preparing)
            assertEquals(0L, relay.monitorSnapshot().sessions.single().requests)
            fun get(url: String) = client.newCall(Request.Builder().url(url).build()).execute()
            val playlist = get(url).use { assertEquals(200, it.code); it.body!!.string() }
            assertEquals(1, reads.get())
            val segment = playlist.lineSequence().first { it.startsWith("http") }
            get(segment).use { assertEquals(200, it.code); assertArrayEquals(byteArrayOf(1, 2, 3), it.body!!.bytes()) }
            val secondPlaylist = get(url).use { assertEquals(200, it.code); it.body!!.string() }
            assertEquals("Only the first GET reuses preparation", 2, reads.get())
            val snapshot = awaitIdleMonitor(relay)
            assertEquals(3L, snapshot.sessions.single().requests)
            assertEquals(0, snapshot.sessions.single().activeRequests)
            assertEquals(playlist.toByteArray().size + secondPlaylist.toByteArray().size + 3L, snapshot.deliveredBytes)
            assertEquals(snapshot.deliveredBytes, snapshot.sessions.single().deliveredBytes)
            val telemetry = Gson().toJson(snapshot)
            assertFalse(telemetry.contains(token)); assertFalse(telemetry.contains("http://"))
            get(url.substringBeforeLast('/').substringBeforeLast('/') + "/unknown/resource.ts").use { assertEquals(502, it.code) }
            assertEquals(1L, relay.monitorSnapshot().failures)
            assertEquals(1L, relay.monitorSnapshot().sessions.single().failures)
            val session = url.substringAfter("/media/").substringBefore('/')
            client.newCall(Request.Builder().url(relay.address + LanRelayProtocol.ROOT + "/session/" + session)
                .header("Authorization", "Bearer $token").delete().build()).execute().use { assertEquals(200, it.code) }
            assertTrue(relay.monitorSnapshot().sessions.isEmpty())
            assertTrue(relay.monitorSnapshot().deliveredBytes > 0)
        }
    }

    @Test fun slowPreparationHasADeadlineAndDoesNotPublishASession() {
        withFixture(upstreamTimeoutMillis = 150) { upstream, relay, client, token ->
            upstream.createContext("/") { exchange ->
                Thread.sleep(400)
                runCatching {
                    val bytes = "#EXTM3U\n#EXTINF:2,\nsegment.ts\n".toByteArray()
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                exchange.close()
            }
            val started = System.nanoTime()
            sessionResponse(relay, client, token, "http://upstream.example:${upstream.address.port}/index.m3u8").use {
                assertEquals(502, it.code)
            }
            assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2000)
            val field = DesktopLanRelay::class.java.getDeclaredField("sessions").apply { isAccessible = true }
            assertTrue((field.get(relay) as Map<*, *>).isEmpty())
            assertEquals(0, awaitIdleMonitor(relay).preparing)
            assertEquals(1L, relay.monitorSnapshot().failures)
        }
    }

    @Test fun redirectsShareThePreparationDeadline() {
        withFixture { upstream, relay, client, token ->
            upstream.createContext("/") { exchange ->
                Thread.sleep(4000)
                runCatching {
                    if (exchange.requestURI.path == "/redirect.m3u8") {
                        exchange.responseHeaders.set("Location", "/index.m3u8")
                        exchange.sendResponseHeaders(302, -1)
                    } else {
                        val bytes = "#EXTM3U\n#EXTINF:2,\nsegment.ts\n".toByteArray()
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                        exchange.responseBody.use { it.write(bytes) }
                    }
                }
                exchange.close()
            }
            val started = System.nanoTime()
            sessionResponse(relay, client, token, "http://upstream.example:${upstream.address.port}/redirect.m3u8").use {
                assertEquals(502, it.code)
            }
            val elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue("Redirects must share the seven-second budget: $elapsed ms", elapsed < 7900)
            val field = DesktopLanRelay::class.java.getDeclaredField("sessions").apply { isAccessible = true }
            assertTrue((field.get(relay) as Map<*, *>).isEmpty())
        }
    }

    private fun sessionResponse(relay: DesktopLanRelay, client: OkHttpClient, token: String, url: String): Response =
        client.newCall(Request.Builder().url(relay.address + LanRelayProtocol.ROOT + "/session")
            .header("Authorization", "Bearer $token")
            .post(Gson().toJson(RelaySessionRequest(url)).toRequestBody("application/json".toMediaType())).build()).execute()

    private fun awaitIdleMonitor(relay: DesktopLanRelay): RelayMonitorSnapshot {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
        while (true) {
            val value = relay.monitorSnapshot()
            if (value.preparing == 0 && value.sessions.all { it.activeRequests == 0 }) return value
            check(System.nanoTime() < deadline) { "Relay did not finish its request" }
            Thread.sleep(10)
        }
    }

    private fun createSession(relay: DesktopLanRelay, client: OkHttpClient, token: String, url: String): String =
        sessionResponse(relay, client, token, url).use {
            assertEquals(200, it.code)
            Gson().fromJson(it.body!!.string(), Map::class.java)["url"].toString()
        }

    private fun withFixture(upstreamTimeoutMillis: Long = 0, test: (HttpServer, DesktopLanRelay, OkHttpClient, String) -> Unit) {
        val loopback = InetAddress.getByName("127.0.0.1")
        val upstream = HttpServer.create(InetSocketAddress(loopback, 0), 0).apply { start() }
        val transport = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dns { listOf(loopback) }
            .callTimeout(upstreamTimeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS).build()
        val client = transport.newBuilder().callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).build()
        val directory = Files.createTempDirectory("relay-assistance-test")
        val relay = DesktopLanRelay(DesktopLanRelaySettings(directory.resolve("settings")), transport)
        try {
            relay.enable(loopback)
            val payload = mapOf("clientId" to "client-123", "code" to relay.pairingCode())
            val token = client.newCall(Request.Builder().url(relay.address + LanRelayProtocol.ROOT + "/pair")
                .post(Gson().toJson(payload).toRequestBody("application/json".toMediaType())).build()).execute().use {
                assertEquals(200, it.code)
                Gson().fromJson(it.body!!.string(), Map::class.java)["token"].toString()
            }
            test(upstream, relay, client, token)
        } finally {
            relay.disable(); upstream.stop(0)
            client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
            Files.walk(directory).sorted(Comparator.reverseOrder()).use { it.forEach(Files::delete) }
        }
    }

    @Test fun pairsRewritesChunkedHlsStreamsRangesAndRevokes() {
        val loopback = InetAddress.getByName("127.0.0.1")
        var range: String? = null
        var revision = 0
        var rotating = false
        val playlistReads = java.util.concurrent.atomic.AtomicInteger()
        val upstream = HttpServer.create(InetSocketAddress(loopback, 0), 0)
        upstream.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            if (path.endsWith("m3u8")) playlistReads.incrementAndGet()
            if (path.endsWith("m3u8")) exchange.responseHeaders.add("Set-Cookie", "relay-cookie=present; Path=/")
            else if (!exchange.requestHeaders.getFirst("Cookie").orEmpty().contains("relay-cookie=present")) {
                exchange.sendResponseHeaders(403, -1); exchange.close(); return@createContext
            }
            val bytes = if (path.endsWith("m3u8")) ("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n" +
                if (rotating) (0 until 24).joinToString("\n", postfix = "\n") { "#EXTINF:2,\npart-${revision * 24 + it}.ts" }.also { revision++ } else "#EXTINF:2,\nsegment.ts\n").toByteArray() else ByteArray(4096) { (it % 255).toByte() }
            range = exchange.requestHeaders.getFirst("Range")
            if (range != null) exchange.responseHeaders.set("Content-Range", "bytes 0-9/4096")
            exchange.sendResponseHeaders(if (range != null) 206 else 200, 0) // deliberately chunked, including HLS
            exchange.responseBody.use { it.write(if (range != null) bytes.copyOf(10) else bytes) }; exchange.close()
        }
        upstream.start()
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dns { listOf(loopback) }.build()
        val dir = Files.createTempDirectory("desktop-relay-test")
        val relay = DesktopLanRelay(DesktopLanRelaySettings(dir.resolve("settings")), client)
        val gson = Gson()
        fun post(path: String, value: Any, token: String? = null): Response {
            val request = Request.Builder().url(relay.address + LanRelayProtocol.ROOT + path)
                .post(gson.toJson(value).toRequestBody("application/json".toMediaType()))
            token?.let { request.header("Authorization", "Bearer $it") }
            return client.newCall(request.build()).execute()
        }
        try {
            relay.enable(loopback)
            post("/session", RelaySessionRequest("http://upstream.example:${upstream.address.port}/index.m3u8")).use { assertEquals(401, it.code) }
            post("/pair", mapOf("clientId" to "client-123", "code" to "invalid")).use { assertEquals(403, it.code) }
            val token = post("/pair", mapOf("clientId" to "client-123", "code" to relay.pairingCode())).use {
                assertEquals(200, it.code); gson.fromJson(it.body!!.string(), Map::class.java)["token"].toString()
            }
            val sessionUrl = post("/session", RelaySessionRequest("http://upstream.example:${upstream.address.port}/index.m3u8"), token).use {
                assertEquals(200, it.code); gson.fromJson(it.body!!.string(), Map::class.java)["url"].toString()
            }
            val manifest = client.newCall(Request.Builder().url(sessionUrl).build()).execute().use { assertEquals(200, it.code); it.body!!.string() }
            assertEquals("The prepared entry playlist must be reused", 1, playlistReads.get())
            assertFalse(manifest.contains("upstream.example"))
            assertTrue(manifest.contains("URI=\"${relay.address}"))
            val segment = manifest.lineSequence().first { it.startsWith("http") }
            client.newCall(Request.Builder().url(segment).build()).execute().use { assertEquals(4096, it.body!!.bytes().size) }
            client.newCall(Request.Builder().url(segment).header("Range", "bytes=0-9").build()).execute().use {
                assertEquals(206, it.code); assertEquals(10, it.body!!.bytes().size); assertEquals("bytes=0-9", range)
            }
            rotating = true
            var rotated = ""
            repeat(90) {
                rotated = client.newCall(Request.Builder().url(sessionUrl).build()).execute().use { response ->
                    assertEquals("Entry playlist must survive resource eviction", 200, response.code)
                    response.body!!.string()
                }
            }
            val keyUrl = Regex("URI=\"([^\"]+)\"").find(rotated)!!.groupValues[1]
            client.newCall(Request.Builder().url(keyUrl).build()).execute().use { assertEquals("Active key must survive rotation", 200, it.code) }
            post("/session", RelaySessionRequest("http://127.0.0.1:${upstream.address.port}/index.m3u8"), token).use { assertEquals(502, it.code) }
            val sessionId = sessionUrl.substringAfter("/media/").substringBefore('/')
            val otherToken = post("/pair", mapOf("clientId" to "client-456", "code" to relay.pairingCode())).use {
                assertEquals(200, it.code); gson.fromJson(it.body!!.string(), Map::class.java)["token"].toString()
            }
            fun release(token: String?): Int {
                val request = Request.Builder().url(relay.address + LanRelayProtocol.ROOT + "/session/" + sessionId).delete()
                token?.let { request.header("Authorization", "Bearer $it") }
                return client.newCall(request.build()).execute().use { it.code }
            }
            assertEquals(401, release(null))
            assertEquals(200, release(otherToken))
            client.newCall(Request.Builder().url(sessionUrl).build()).execute().use { assertEquals(200, it.code) }
            assertEquals(200, release(token))
            assertEquals(200, release(token)) // Releasing twice is safe.
            client.newCall(Request.Builder().url(sessionUrl).build()).execute().use { assertEquals(401, it.code) }
            relay.forgetDevices()
            client.newCall(Request.Builder().url(segment).build()).execute().use { assertEquals(401, it.code) }
        } finally {
            relay.disable(); upstream.stop(0); client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
            Files.walk(dir).sorted(Comparator.reverseOrder()).use { it.forEach(Files::delete) }
        }
    }
}
