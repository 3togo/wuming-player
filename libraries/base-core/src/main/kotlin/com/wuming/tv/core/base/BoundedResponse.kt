package com.wuming.tv.core.base

import okhttp3.ResponseBody
import java.io.IOException
import java.io.InputStream
import okio.buffer
import okio.source

/** Limits decompressed bytes before materializing a resource. The caller owns the response or stream. */
object BoundedResponse {
    class LimitExceeded(limit: Int) : IOException("Resource exceeds the ${limit / 1024} KiB limit")

    const val CONFIG_BYTES = 16 * 1024 * 1024
    const val PARSER_BYTES = 1024 * 1024

    @JvmStatic
    @Throws(IOException::class)
    fun text(body: ResponseBody, limit: Int): String {
        checkSize(body, limit)
        // Keep OkHttp's BOM and Content-Type charset handling.
        return body.string()
    }

    @JvmStatic
    @Throws(IOException::class)
    fun bytes(body: ResponseBody, limit: Int): ByteArray {
        checkSize(body, limit)
        return body.bytes()
    }

    @JvmStatic
    @Throws(IOException::class)
    fun bytes(input: InputStream, limit: Int): ByteArray {
        val source = input.source().buffer()
        if (source.request(limit.toLong() + 1)) throw LimitExceeded(limit)
        return source.readByteArray()
    }

    private fun checkSize(body: ResponseBody, limit: Int) {
        if (body.contentLength() > limit || body.source().request(limit.toLong() + 1)) {
            throw LimitExceeded(limit)
        }
    }
}
