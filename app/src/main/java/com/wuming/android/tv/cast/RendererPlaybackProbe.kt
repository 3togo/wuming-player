package com.wuming.android.tv.cast

import com.wuming.android.tv.App
import com.wuming.android.tv.bean.CastVideo
import com.wuming.android.tv.bean.Device
import com.wuming.android.tv.dlna.DLNACast
import com.wuming.tv.core.base.ByteRanges
import com.wuming.tv.core.playback.renderer.RendererProbeSamples
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.util.UUID

/** Short-lived server serves only synthetic fixtures under an unguessable per-test URL. */
class RendererPlaybackProbe(private val device: Device, val sample: RendererProbeSamples.Sample) : AutoCloseable {
    val runId: String = UUID.randomUUID().toString()
    @Volatile var fetchedMedia = false
        private set
    private var cast: DLNACast? = null
    @Volatile private var closed = false
    private val server = object : NanoHTTPD(0) {
        override fun serve(session: IHTTPSession): Response {
            if (closed || !session.uri.startsWith("/$runId/") || session.method !in listOf(Method.GET, Method.HEAD))
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
            val file = session.uri.removePrefix("/$runId/")
            val bytes = RendererProbeSamples.resource(file)
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
            var start = 0L
            var end = bytes.size.toLong() - 1
            val range = session.headers["range"]
            if (range != null) {
                val suffix = ByteRanges.parseSuffixRequest(range)
                if (suffix != null) start = (bytes.size.toLong() - suffix).coerceAtLeast(0)
                else {
                    val requested = ByteRanges.parseRequest(range) ?: return invalidRange(bytes.size)
                    start = requested.start
                    if (requested.endInclusive != null) end = requested.endInclusive!!.coerceAtMost(end)
                }
                if (start < 0 || start > end || start >= bytes.size) return invalidRange(bytes.size)
            }
            if (session.method == Method.GET && file.substringAfterLast('.') in setOf("mp4", "ts", "m4s")) fetchedMedia = true
            return newFixedLengthResponse(if (range == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT,
                RendererProbeSamples.mime(file), ByteArrayInputStream(bytes, start.toInt(), (end - start + 1).toInt()), end - start + 1).apply {
                addHeader("Accept-Ranges", "bytes")
                addHeader("Cache-Control", "no-store")
                if (range != null) addHeader("Content-Range", "bytes $start-$end/${bytes.size}")
            }
        }
        private fun invalidRange(size: Int) = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "Invalid range").apply {
            addHeader("Content-Range", "bytes */$size")
        }
    }
    /** Called on UI thread; server startup only binds a local socket. */
    fun start() {
        check(!closed)
        server.start(5000, true)
        try {
            val url = "http://${com.github.catvod.utils.Util.getIp()}:${server.listeningPort}/$runId/${sample.file}"
            cast = DLNACast(CastVideo("DLNA capability test", url, 0, 15000, emptyMap(), null, null, null, null), Runnable {}).also { it.cast(device) }
            App.post({ close() }, 45000)
        } catch (error: Exception) { close(); throw error }
    }
    override fun close() {
        if (closed) return
        closed = true
        cast?.stop()
        cast?.release()
        cast = null
        com.wuming.android.tv.utils.Task.execute { server.stop() }
    }
}
