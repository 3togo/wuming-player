package com.wuming.tv.desktop.player

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.*
import org.junit.Test

class HlsSharedAdFilterTest {
    @Test fun desktopUsesSharedDetectorsForSameLowerAndHigherResolutionInsertions() {
        for (mode in listOf("same", "lower", "higher")) {
            val programmeSps = if (mode == "higher") PROGRAMME else AD
            val insertionSps = if (mode == "lower") PROGRAMME else AD
            val features = mapOf("before.ts" to 100.0, "a.ts" to 1.48, "b.ts" to 8.44,
                "c.ts" to 12.44, "d.ts" to 15.0, "after.ts" to 160.0)
            val requests = ConcurrentHashMap<String, Int>()
            val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
            val manifest = "#EXTM3U\n#EXT-X-TARGETDURATION:60\n#EXTINF:60,\nbefore.ts\n" +
                "#EXT-X-DISCONTINUITY\n#EXTINF:6.96,\na.ts\n#EXTINF:4,\nb.ts\n" +
                "#EXTINF:2.56,\nc.ts\n#EXTINF:3.84,\nd.ts\n" +
                "#EXT-X-DISCONTINUITY\n#EXTINF:60,\nafter.ts\n#EXT-X-ENDLIST\n"
            server.createContext("/") { exchange ->
                val name = exchange.requestURI.path.substringAfterLast('/')
                val bytes = if (name == "episode.m3u8") manifest.toByteArray() else ts(
                    if (name == "before.ts" || name == "after.ts") programmeSps else insertionSps,
                    features.getValue(name))
                if (exchange.requestHeaders.getFirst("Range") != null) {
                    assertEquals("bytes=0-65535", exchange.requestHeaders.getFirst("Range"))
                    assertEquals("identity", exchange.requestHeaders.getFirst("Accept-Encoding"))
                    assertEquals("fixture", exchange.requestHeaders.getFirst("Cookie"))
                    requests.merge(name, 1, Int::plus)
                }
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            server.start()
            val base = "http://127.0.0.1:${server.address.port}/"
            var prepared: PreparedPlaybackTarget? = null
            try {
                prepared = HlsCompatibilityPreparer().prepare(base + "episode.m3u8", mapOf("Cookie" to "fixture"))
                val filtered = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI(prepared.location)).GET().build(), HttpResponse.BodyHandlers.ofString()).body()
                val segments = filtered.lines().filter { it.isNotBlank() && !it.startsWith('#') }.map {
                    val encoded = URI(it).path.substringAfterLast('/')
                    URI(String(Base64.getUrlDecoder().decode(encoded))).path.substringAfterLast('/')
                }
                assertEquals(mode, listOf("before.ts", "after.ts"), segments)
                assertEquals(mode, features.keys, requests.keys)
                assertTrue(mode, requests.values.all { it == 1 })
            } finally {
                prepared?.resource?.close()
                server.stop(0)
            }
        }
    }

    private fun ts(sps: String, seconds: Double): ByteArray {
        val ticks = Math.round(seconds * 90000)
        val pes = byteArrayOf(0, 0, 1, 0xe0.toByte(), 0, 0, 0x80.toByte(), 0x80.toByte(), 5,
            (0x21 or ((ticks shr 29).toInt() and 14)).toByte(), (ticks shr 22).toByte(),
            (((ticks shr 14).toInt() and 254) or 1).toByte(), (ticks shr 7).toByte(), ((ticks shl 1) or 1).toByte()) +
            Base64.getDecoder().decode(sps)
        val pmt = byteArrayOf(0, 2, 0xb0.toByte(), 18, 0, 1, 0xc1.toByte(), 0, 0,
            0xe1.toByte(), 0, 0xf0.toByte(), 0, 0x1b, 0xe1.toByte(), 0, 0xf0.toByte(), 0, 0, 0, 0, 0)
        return packet(4096, true, pmt) + packet(256, true, pes) +
            packet(8191, false, byteArrayOf()) + packet(8191, false, byteArrayOf())
    }

    private fun packet(pid: Int, start: Boolean, payload: ByteArray): ByteArray = ByteArray(188) { 0xff.toByte() }.apply {
        this[0] = 0x47
        this[1] = ((pid shr 8) or if (start) 0x40 else 0).toByte()
        this[2] = pid.toByte()
        this[3] = 0x10
        payload.copyInto(this, 4)
    }

    private companion object {
        const val PROGRAMME = "AAABZ2QAH6zZQFAFuwFqAgICgAAAAwCAAAAZB4wYywAAAAFo74/L"
        const val AD = "AAABZ2QAMqzZgHgCJ+XAWoCAgKAAAAMAIAAABkHjBjNAAAAAAWjpePLIsA=="
    }
}
