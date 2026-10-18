package com.wuming.android.tv.server.process

import com.wuming.android.tv.server.Nano
import com.wuming.android.tv.server.impl.Process
import com.wuming.tv.core.cache.ExpiringLruCache
import com.wuming.tv.core.cache.MemoryBudget
import com.wuming.tv.core.cache.MemoryBudget.Companion.current
import com.wuming.tv.core.cache.Weights
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.function.ToLongFunction
import kotlin.concurrent.Volatile

/**
 * Server process handler for serving purified M3U8 content.
 * Serves the cached purified M3U8 content at /m3u8 endpoint.
 * Uses session-keyed storage to avoid race conditions between concurrent playback sessions.
 */
open class M3u8Proxy : Process {
    override fun isRequest(session: IHTTPSession, url: String): Boolean = url.startsWith("/m3u8")

    override fun doResponse(
        session: IHTTPSession,
        url: String,
        files: Map<String, String>
    ): NanoHTTPD.Response {
        try {
            val key: String = extractKey(url)
            val m3u8Content: String? = lookup(key)
            if (m3u8Content == null || m3u8Content.isEmpty()) {
                com.wuming.android.tv.utils.AppLog.w("M3u8Proxy", "no content for key=$key")
                return Nano.error("No M3U8 content")
            }
            val stream: ByteArrayInputStream =
                ByteArrayInputStream(m3u8Content.toByteArray(StandardCharsets.UTF_8))
            return NanoHTTPD.newChunkedResponse(
                NanoHTTPD.Response.Status.OK,
                "application/vnd.apple.mpegurl",
                stream
            )
        } catch (e: Exception) {
            return Nano.error(e.message)
        }
    }

    private fun extractKey(url: String): String {
        val queryStart: Int = url.indexOf('?')
        if (queryStart < 0) return DEFAULT_KEY
        val query: String = url.substring(queryStart + 1)
        for (param: String in query.split("&".toRegex()).dropLastWhile { it.isEmpty() }
            .toTypedArray()) {
            if (param.startsWith("key=")) return param.substring(4)
        }
        return DEFAULT_KEY
    }

    @Suppress("NON_FINAL_MEMBER_IN_OBJECT")
    companion object {
        private const val DEFAULT_KEY = "default"
        private const val MAX_SESSIONS = 20
        private val SESSION_TTL = TimeUnit.HOURS.toMillis(6)
        private val MEMORY = current()
        private val MAX_TOTAL_SESSION_BYTES: Long = MEMORY.weightedBytes(0.01)

        /** The playlist owned by playback itself, so it is never evicted or expired.  */
        @Volatile
        private var defaultContent: String? = null

        @JvmStatic open fun getContent(): String? = defaultContent

        @JvmStatic open fun setContent(m3u8Content: String?) {
            defaultContent = m3u8Content
        }

        /** Session playlists are dropped by retained bytes and by recency, oldest first.  */
        private val sessionContent = ExpiringLruCache<String?, String?>(
            MAX_SESSIONS,
            SESSION_TTL,
            ToLongFunction { text: String? -> Weights.utf8(text) },
            MAX_TOTAL_SESSION_BYTES
        )

        @JvmStatic
        open fun setContent(key: String?, m3u8Content: String?) {
            if (key == null || DEFAULT_KEY == key) {
                defaultContent = m3u8Content
            } else {
                sessionContent.put(key, m3u8Content)
            }
        }

        @JvmStatic
        open fun clearContent(key: String?) {
            if (key == null) return
            if (DEFAULT_KEY == key) {
                defaultContent = null
            } else {
                sessionContent.remove(key)
            }
        }

        @JvmStatic
        private fun lookup(key: String?): String? =
            if (DEFAULT_KEY == key) defaultContent else sessionContent.get(key)
    }
}
