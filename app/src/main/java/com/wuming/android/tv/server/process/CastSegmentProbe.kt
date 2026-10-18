package com.wuming.android.tv.server.process

import com.wuming.tv.core.base.ByteRanges
import java.io.IOException
import java.io.InterruptedIOException
import java.util.regex.Pattern
import okhttp3.Call
import okhttp3.ResponseBody

/** Bounded HEAD/range sizing; no response payload is read into application buffers. */
internal object CastSegmentProbe {
    const val MAX_SEGMENTS = 10_000
    private val scheduler = CastWorkScheduler(4, 0, "cast-segment-probe")
    private val contentRange = Pattern.compile("bytes\\s+0-0/(\\d+)", Pattern.CASE_INSENSITIVE)

    fun interface Calls {
        fun create(index: Int, method: String, range: String?): Call
    }

    @JvmStatic
    @Throws(IOException::class)
    fun sizes(count: Int, calls: Calls): LongArray {
        if (count <= 0 || count > MAX_SEGMENTS) throw IOException("Invalid cast probe segment count")
        val scope = CastCallScope()
        scope.check()
        val sizes = LongArray(count)
        val job = CastWorkScheduler.Job(count, 4, { index, _, _ ->
            scope.check()
            sizes[index] = size(index, calls, scope)
        }, scope::close)
        try {
            scheduler.submit(job)
            job.await()
            scope.check()
            var total = 0L
            for (size in sizes) {
                if (size <= 0 || size > Long.MAX_VALUE - total) throw IOException("Cast probe length overflow")
                total += size
            }
            return sizes
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Cast probe interrupted").apply { initCause(error) }
        } finally {
            scope.close()
            scheduler.cancel(job)
        }
    }

    @Throws(IOException::class)
    private fun size(index: Int, calls: Calls, scope: CastCallScope): Long {
        try {
            request(calls.create(index, "HEAD", null), false, scope)?.let { return it }
        } catch (_: IOException) {
            // A failed/unsupported HEAD may still have a valid range response.
        }
        scope.check() // Cancellation must not launch a fallback request.
        return request(calls.create(index, "GET", "bytes=0-0"), true, scope)
            ?: throw IOException("Unable to determine cast segment size")
    }

    @Throws(IOException::class)
    private fun request(call: Call, range: Boolean, scope: CastCallScope): Long? {
        scope.register(call)
        try {
            call.execute().use { response ->
                scope.check()
                val body = response.body
                if (!range) return if (response.isSuccessful) positiveLength(body) else null
                if (response.code == 206) return rangeTotal(response.header("Content-Range"))
                return if (response.code == 200) positiveLength(body) else null
            }
        } finally {
            scope.remove(call)
        }
    }

    private fun positiveLength(body: ResponseBody?): Long? =
        if (body != null && body.contentLength() > 0) body.contentLength() else null

    @JvmStatic
    fun rangeTotal(value: String?): Long? {
        if (value == null) return null
        // Match Java String.trim(), including its narrower whitespace definition.
        val trimmed = value.trim { it <= ' ' }
        // Preserve the probe's exact 0-0 wire form before using shared range arithmetic.
        if (!contentRange.matcher(trimmed).matches()) return null
        return ByteRanges.parseResponse(trimmed)?.total?.takeIf { it > 0 }
    }
}
