package com.wuming.tv.core.base

import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException
import java.io.InterruptedIOException

/** Bounded reads for small metadata/playlist responses. The caller owns the response. */
object BoundedHttpText {
    @JvmStatic
    @Throws(IOException::class)
    fun read(response: Response, maxBytes: Int): String {
        require(maxBytes > 0) { "Response limit must be positive" }
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Response read cancelled")
        if (!response.isSuccessful) throw IOException("HTTP " + response.code)
        val body = response.body
        if (body.contentLength() > maxBytes) throw IOException("Response exceeds size limit")
        // Check the actual bytes too: Content-Length may be absent or incorrect.
        response.peekBody((maxBytes + 1).toLong()).use { bounded ->
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Response read cancelled")
            if (bounded.contentLength() > maxBytes) throw IOException("Response exceeds size limit")
            return bounded.string()
        }
    }
}
