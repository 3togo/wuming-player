package com.wuming.tv.core.playback

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/** The same bounded, cancellable HTTP prefix read on all JVM platforms. Client
 * configuration (proxy, DNS, cookies and authentication) belongs to the host. */
class HlsHttpProbeTransport @JvmOverloads constructor(
    private val client: OkHttpClient,
    private val defaultUserAgent: String? = null,
) : HlsSegmentProbeSession.Transport, AutoCloseable {
    private var closed = false
    private var active: Call? = null

    @Throws(IOException::class)
    override fun readPrefix(url: String, headers: Map<String, String>, maxBytes: Int,
                            timeoutMillis: Long): HlsSegmentProbeSession.Prefix {
        require(maxBytes > 0 && timeoutMillis > 0)
        val request = Request.Builder().url(url)
        headers.forEach { (name, value) -> request.header(name, value) }
        if (defaultUserAgent != null && headers.keys.none { it.equals("User-Agent", true) }) {
            request.header("User-Agent", defaultUserAgent)
        }
        val current = client.newCall(request.get().build())
        current.timeout().timeout(timeoutMillis, TimeUnit.MILLISECONDS)
        synchronized(this) {
            if (closed) throw IOException("HLS probe transport closed")
            check(active == null) { "HLS probe transport is sequential" }
            active = current
        }
        try {
            current.execute().use { response ->
                val bytes = if (response.isSuccessful) response.peekBody(maxBytes.toLong()).use { it.bytes() }
                    else byteArrayOf()
                return HlsSegmentProbeSession.Prefix(response.code, response.header("Content-Range"), bytes)
            }
        } finally {
            synchronized(this) { if (active === current) active = null }
        }
    }

    override fun close() {
        synchronized(this) {
            closed = true
            active?.cancel()
            active = null
        }
    }
}
