package com.wuming.android.tv.player.danmaku

import android.net.Uri
import com.wuming.android.tv.bean.Danmaku
import com.wuming.android.tv.utils.UrlUtil
import com.github.catvod.net.OkHttp
import master.flame.danmaku.danmaku.parser.android.AndroidFileSource
import okhttp3.Call
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException

/** One cancellable request. The parser receives bounded data independent of the HTTP response. */
class Loader : AutoCloseable {
    companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
    }

    private var closed = false
    private var call: Call? = null
    private var source: AndroidFileSource? = null

    @Throws(IOException::class)
    fun load(item: Danmaku) {
        val uri = Uri.parse(item.getRealUrl())
        val scheme = uri.scheme
        val data: ByteArray
        if ("file".equals(scheme, ignoreCase = true)) {
            val path = uri.path
            if (path.isNullOrEmpty()) throw IOException("Danmaku file path is empty")
            FileInputStream(path).use { data = readBounded(it) }
        } else {
            if (!UrlUtil.isHttpScheme(scheme)) {
                throw IOException("Unsupported danmaku URI scheme")
            }
            val request =
                try {
                    OkHttp.newCall(uri.toString())
                } catch (
                    e: IllegalArgumentException,
                ) {
                    throw IOException("Invalid danmaku URL", e)
                }
            synchronized(this) {
                checkOpen()
                call = request
            }
            try {
                loadResponse(request.execute())
            } finally {
                synchronized(this) { call = null }
            }
            return
        }
        synchronized(this) {
            checkOpen()
            source?.release()
            source = AndroidFileSource(ByteArrayInputStream(data))
        }
    }

    @Throws(IOException::class)
    fun loadResponse(response: Response) {
        response.use {
            val body = response.body
            if (!response.isSuccessful) throw IOException("Danmaku HTTP " + response.code)
            if (body.contentLength() > MAX_BYTES) throw IOException("Danmaku exceeds size limit")
            load(body.byteStream())
        }
    }

    @Throws(IOException::class)
    fun load(stream: InputStream) {
        val data = stream.use { readBounded(it) }
        synchronized(this) {
            checkOpen()
            source?.release()
            source = AndroidFileSource(ByteArrayInputStream(data))
        }
    }

    private fun readBounded(stream: InputStream): ByteArray {
        val data = OwnedBuffer()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count == -1) break
            synchronized(this) { checkOpen() }
            if (count > MAX_BYTES - data.size()) throw IOException("Danmaku exceeds size limit")
            data.write(buffer, 0, count)
        }
        return data.finish()
    }

    /** Transfer a single read's buffer without retaining spare capacity. */
    private class OwnedBuffer : ByteArrayOutputStream() {
        fun finish(): ByteArray = if (count == buf.size) buf else toByteArray()
    }

    private fun checkOpen() {
        if (closed || Thread.currentThread().isInterrupted) throw InterruptedIOException("Danmaku canceled")
    }

    @Synchronized fun getDataSource(): AndroidFileSource? = source

    @Synchronized override fun close() {
        closed = true
        call?.cancel()
        source?.release()
        call = null
        source = null
    }
}
