package com.wuming.android.tv.server

import com.wuming.android.tv.server.process.CastMediaRelay
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class CastDashRelayTest {
    @Test(timeout = 15000)
    fun manifestProbeRangeDoesNotTruncateXmlOrLeakPartialResponseHeaders() {
        val manifest = """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"><Period/></MPD>"""
        val seenRange = AtomicReference<String>()
        val upstream = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                seenRange.set(session.headers["range"])
                val partial = session.headers.containsKey("range")
                val bytes = (if (partial) manifest.take(8) else manifest).toByteArray()
                return newFixedLengthResponse(
                    if (partial) Response.Status.PARTIAL_CONTENT else Response.Status.OK,
                    "application/dash+xml", ByteArrayInputStream(bytes), bytes.size.toLong(),
                ).apply { if (partial) addHeader("Content-Range", "bytes 0-7/${manifest.length}") }
            }
        }
        upstream.start()
        val relay = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response =
                CastMediaRelay().doResponse(session, session.uri, emptyMap())
        }
        relay.start()
        val client = OkHttpClient()
        try {
            val target = "http://127.0.0.1:${upstream.listeningPort}/source.mpd"
            val token = CastMediaAccess.registerRelay(CastMediaAccess.Relay(target, emptyMap(), Long.MAX_VALUE))
            val path = "/cast-relay/$token/stream.mpd"
            for (method in listOf("GET", "HEAD")) {
                val request = Request.Builder().url("http://127.0.0.1:${relay.listeningPort}$path")
                    .method(method, null).header("Range", "bytes=0-7").build()
                client.newCall(request).execute().use { response ->
                    assertEquals(200, response.code)
                    assertNull(seenRange.get())
                    assertNull(response.header("Content-Range"))
                    assertEquals(manifest.length.toString(), response.header("Content-Length"))
                    assertEquals(if (method == "HEAD") "" else manifest, response.body.string())
                }
            }
        } finally {
            CastMediaAccess.clear()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            relay.stop()
            upstream.stop()
        }
    }
}
