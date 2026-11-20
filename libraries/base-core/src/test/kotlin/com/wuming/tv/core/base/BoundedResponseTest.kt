package com.wuming.tv.core.base

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSource
import okio.buffer
import okio.source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class BoundedResponseTest {
    @Test fun acceptsLimitAndPreservesCharsetAndBom() {
        val text = "語"
        val encoded = byteArrayOf(-1, -2) + text.toByteArray(Charsets.UTF_16LE)
        assertEquals(text, BoundedResponse.text(encoded.toResponseBody("text/plain; charset=utf-8".toMediaType()), encoded.size))
    }

    @Test fun rejectsOversizeKnownAndDecodedUnknownLengthBodies() {
        assertThrows(BoundedResponse.LimitExceeded::class.java) {
            BoundedResponse.bytes(ByteArray(33).toResponseBody(), 32)
        }
        val encoded = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { it.write(ByteArray(4096)) }
        }
        val body = object : ResponseBody() {
            private val input = GZIPInputStream(ByteArrayInputStream(encoded.toByteArray())).source().buffer()
            override fun contentType() = null
            override fun contentLength() = -1L
            override fun source(): BufferedSource = input
        }
        body.use {
            assertThrows(BoundedResponse.LimitExceeded::class.java) { BoundedResponse.bytes(it, 1024) }
        }
    }
}
