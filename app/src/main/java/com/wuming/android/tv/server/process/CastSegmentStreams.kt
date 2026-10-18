package com.wuming.android.tv.server.process

import com.wuming.tv.core.playback.CastRelayTransfer
import com.wuming.tv.core.playback.OrderedPrefetchStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Response

/** Android HTTP port; ordering, worker admission and bounded buffering live in playback-core. */
internal object CastSegmentStreams {
    @JvmStatic
    fun unknown(segments: List<String>, headers: Map<String, String>): InputStream {
        val urls = segments.toList()
        val auth = headers.toMap()
        return OrderedPrefetchStream(urls.size, { index -> request(urls[index], auth, 0, -1, -1) })
    }

    @JvmStatic
    fun range(media: AggregatedMedia, headers: Map<String, String>, start: Long, length: Long): InputStream {
        require(start >= 0 && length > 0 && start < media.total && length <= media.total - start)
        val first = media.segmentFor(start)
        val last = media.segmentFor(start + length - 1)
        val auth = headers.toMap()
        return OrderedPrefetchStream(last - first + 1, { item ->
            val index = first + item
            val segmentStart = media.starts[index]
            val segmentLength = media.starts[index + 1] - segmentStart
            val from = maxOf(0, start - segmentStart)
            val end = minOf(segmentLength, start + length - segmentStart)
            request(media.segments[index], auth, from, end - from, segmentLength)
        })
    }

    private fun request(url: String, headers: Map<String, String>, start: Long,
                        length: Long, total: Long): OrderedPrefetchStream.Request {
        val networkHeaders = headers.filterKeys {
            !it.equals("Range", true) && !it.equals("Accept-Encoding", true)
        } + ("Accept-Encoding" to "identity")
        val range = if (length >= 0 && (start > 0 || length != total)) "bytes=$start-${start + length - 1}" else null
        val call = RelayHttpClient.upstreamCall(url, networkHeaders, null, range)
        val active = AtomicReference<Response?>()
        return object : OrderedPrefetchStream.Request {
            override val length = length
            override fun open(): InputStream {
                val response = call.execute()
                active.set(response)
                try {
                    if (call.isCanceled()) throw IOException("Cast segment cancelled")
                    if (response.code != 200 && response.code != 206) throw IOException("Cast segment HTTP ${response.code}")
                    val body = response.body
                    val encoding = response.header("Content-Encoding")
                    if (!encoding.isNullOrBlank() && !encoding.equals("identity", true))
                        throw IOException("Cast segment returned encoded bytes")
                    if (total < 0 && response.code != 200)
                        throw IOException("Cast segment returned an unsolicited partial response")
                    if (total >= 0) {
                        if (response.code == 206) {
                            val slice = CastRelayTransfer.slice(206,
                                if (body.contentLength() >= 0) body.contentLength() else length,
                                response.header("Content-Range"), null, response.header("Content-Encoding"))
                            if (slice == null || slice.start != start || slice.length != length || slice.total != total)
                                throw IOException("Cast segment returned a different byte range")
                        } else if (body.contentLength() >= 0 && body.contentLength() != total) {
                            throw IOException("Cast segment size changed since probe")
                        }
                    }
                    val input = body.byteStream()
                    if (response.code == 200) {
                        var remaining = start
                        while (remaining > 0) {
                            val skipped = input.skip(remaining)
                            if (skipped > 0) remaining -= skipped else {
                                if (input.read() < 0) throw EOFException("Cast segment ended during seek")
                                remaining--
                            }
                        }
                    }
                    return object : FilterInputStream(input) {
                        override fun close() { active.getAndSet(null)?.close() }
                    }
                } catch (failure: Exception) {
                    active.getAndSet(null)?.close()
                    throw failure
                }
            }
            override fun cancel() {
                call.cancel()
                active.getAndSet(null)?.close()
            }
        }
    }
}
