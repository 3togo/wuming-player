package com.wuming.tv.core.playback

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/** Bounded HTTP range transport for the shared MP4 transformer. No URL or key is logged. */
class CencHttpMedia internal constructor(
    private val url: String, private val headers: Map<String, String>, key: ByteArray,
) {
    private val metadata: CencMp4Transformer
    val length: Long get() = metadata.length

    init {
        val first = fetch(0, 16)
        metadata = CencMp4Transformer.load(first.second, key) { offset, size ->
            if (offset == 0L && size == 16) first.first else fetch(offset, size).first
        }
    }

    fun read(offset: Long, size: Int): ByteArray {
        require(size in 1..1024 * 1024 && offset >= 0 && offset <= length - size)
        return metadata.transform(offset, fetch(offset, size).first)
    }

    private fun fetch(offset: Long, size: Int): Pair<ByteArray, Long> {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Playback interrupted")
        val builder = Request.Builder().url(url)
        headers.forEach { (name, value) -> builder.header(name, value) }
        builder.header("Accept-Encoding", "identity").header("Range", "bytes=$offset-${offset + size - 1}")
        return client.newCall(builder.build()).execute().use { response ->
            if (response.code != 206) throw IOException("CENC media range HTTP ${response.code}")
            val range = com.wuming.tv.core.base.ByteRanges.parseResponse(response.header("Content-Range"))
                ?: throw IOException("Invalid media range response")
            val total = range.total ?: throw IOException("Unknown media length")
            if (range.start != offset || range.endInclusive != offset + size - 1 || total <= range.endInclusive)
                throw IOException("Media range extent mismatch")
            val source = response.body?.source() ?: throw IOException("Empty media range")
            if (source.request(size.toLong() + 1)) throw IOException("Oversized media range")
            val bytes = source.readByteArray()
            if (bytes.size != size) throw IOException("Truncated media range")
            bytes to total
        }
    }

    companion object {
        private val client by lazy { OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build() }
    }
}
