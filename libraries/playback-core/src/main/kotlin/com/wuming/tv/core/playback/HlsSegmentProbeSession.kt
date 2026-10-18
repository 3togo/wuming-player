package com.wuming.tv.core.playback

import java.io.IOException
import java.util.function.BooleanSupplier
import java.util.function.LongSupplier

/** Shared request policy, deadlines, response validation and decoding. The transport
 * port only performs a cancellable HTTP request and reads a bounded response prefix. */
class HlsSegmentProbeSession @JvmOverloads constructor(
    headers: Map<String, String>,
    private val transport: Transport,
    private val canceled: BooleanSupplier = BooleanSupplier { false },
    private val clock: LongSupplier = LongSupplier(System::nanoTime),
) : HlsResolutionAdFilter.Probe {
    fun interface Transport {
        @Throws(IOException::class)
        fun readPrefix(url: String, headers: Map<String, String>, maxBytes: Int, timeoutMillis: Long): Prefix
    }

    class Prefix(val status: Int, val contentRange: String?, val bytes: ByteArray)

    private val headers = headers.toMap()
    private var deadline: Long? = null
    private val decoder = TsStreamProbe.create()
    var requestCount: Int = 0
        private set

    @Throws(IOException::class)
    override fun read(url: String): HlsResolutionAdFilter.VideoInfo? {
        val now = clock.asLong
        val end = deadline ?: (now + TOTAL_TIMEOUT_MILLIS * 1_000_000).also { deadline = it }
        val remaining = (end - now) / 1_000_000
        if (canceled.asBoolean || Thread.currentThread().isInterrupted || remaining <= 0 || requestCount >= MAX_REQUESTS) {
            throw IOException("HLS segment probe budget exhausted or canceled")
        }
        requestCount++
        val requestHeaders = headers.filterKeys {
            !it.equals("Range", true) && !it.equals("Accept-Encoding", true)
        }.toMutableMap().apply {
            put("Range", "bytes=0-${MAX_BYTES - 1}")
            put("Accept-Encoding", "identity")
        }
        val prefix = transport.readPrefix(url, requestHeaders, MAX_BYTES, minOf(remaining, REQUEST_TIMEOUT_MILLIS))
        if (canceled.asBoolean || Thread.currentThread().isInterrupted || clock.asLong >= end) {
            throw IOException("HLS segment probe expired or canceled")
        }
        if (prefix.status !in 200..299) throw IOException("Segment probe HTTP ${prefix.status}")
        if (prefix.status == 206 && !prefix.contentRange.orEmpty().startsWith("bytes 0-")) return null
        if (prefix.bytes.size > MAX_BYTES) return null
        val offset = HlsManifestDetector.transportStreamOffset(prefix.bytes)
        if (offset < 0) return null
        return decoder.read(if (offset == 0) prefix.bytes else prefix.bytes.copyOfRange(offset, prefix.bytes.size))
    }

    companion object {
        const val MAX_BYTES = TsStreamProbe.MAX_BYTES
        const val MAX_REQUESTS = 24
        const val REQUEST_TIMEOUT_MILLIS = 3_000L
        const val TOTAL_TIMEOUT_MILLIS = 15_000L
    }
}
