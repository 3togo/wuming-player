package com.wuming.tv.core.playback

import com.wuming.tv.core.playback.PlaybackTransportPlan
import java.io.IOException
import java.io.InputStream
import java.lang.AutoCloseable
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.concurrent.Volatile
import kotlin.math.max
import kotlin.math.min
import okhttp3.*
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** An explicit, bounded reachability check. Does not resolve, save, delete, or decode.  */
class OpenDriveStreamCheck @JvmOverloads constructor(
    client: OkHttpClient,
    private val localProxy: (HttpUrl) -> Boolean = { false }
) : AutoCloseable {
    enum class State {
        MEDIA_BYTES,
        HLS_SEGMENT,
        HTTP_ERROR,
        TEXT_RESPONSE,
        INVALID_PLAYLIST,
        LIMIT,
        CANCELLED,
        NETWORK_ERROR,
        UNSUPPORTED
    }

    @JvmRecord
    data class Result(val state: State?, val httpStatus: Int, val bytes: Int)

    private val client: OkHttpClient

    @Volatile
    private var current: Call? = null

    @Volatile
    private var cancelled = false
    private var requests = 0
    private var deadline: Long = 0

    constructor() : this(
        OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS).build()
    )

    init {
        this.client = client.newBuilder().cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false).followSslRedirects(false).build()
    }

    fun check(value: String?, headers: Map<String, String>?): Result {
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        requests = 0
        var url: HttpUrl? = (if (value == null) "" else value).toHttpUrlOrNull()
        if (url == null || !(allowed(url) || localProxy(url))) {
            return Result(
                State.UNSUPPORTED,
                0,
                0
            )
        }
        var clean: Map<String, String> =
            PlaybackTransportPlan.Companion.parse(value, null, headers).networkHeadersAccessor()
        try {
            for (depth: Int in 0..3) {
                var sample: Sample = fetch(url!!, clean, SAMPLE)
                if (sample.failure != null) return sample.failure
                val prefix: String = kotlin.text.String(sample.bytes!!, StandardCharsets.UTF_8)
                    .replaceFirst("^\uFEFF".toRegex(), "").trim { it <= ' ' }
                if (!prefix.startsWith("#EXTM3U")) {
                    if (prefix.startsWith(
                            "<"
                        ) || prefix.startsWith("{") || prefix.startsWith("[") ||
                        sample.type!!.startsWith("text/") || sample.type.contains("json") ||
                        sample.type.contains(
                            "xml"
                        )
                    ) {
                        return Result(
                            State.TEXT_RESPONSE,
                            sample.status,
                            sample.bytes.size
                        )
                    }
                    return Result(
                        if (sample.bytes.size >= 16) {
                            if (depth == 0) State.MEDIA_BYTES else State.HLS_SEGMENT
                        } else {
                            State.TEXT_RESPONSE
                        },
                        sample.status,
                        sample.bytes.size
                    )
                }
                sample = fetch(sample.url!!, sample.headers!!, PLAYLIST + 1)
                if (sample.failure != null) return sample.failure
                if (sample.bytes!!.size > PLAYLIST) return Result(State.LIMIT, sample.status, 0)
                val playlist: String = kotlin.text.String(sample.bytes, StandardCharsets.UTF_8)
                var next: HttpUrl? = null
                for (line: String in playlist.split("\\r?\\n".toRegex())
                    .dropLastWhile { it.isEmpty() }.toTypedArray()) {
                    var line: String = line
                    line = line.trim { it <= ' ' }
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        next = sample.url!!.resolve(line)
                        break
                    }
                }
                if (next == null || !allowed(next) || ("https" != next.scheme)) {
                    return Result(
                        State.INVALID_PLAYLIST,
                        sample.status,
                        0
                    )
                }
                clean = Companion.forwarded(sample.url!!, next, sample.headers!!)
                url = next
            }
            return Result(State.LIMIT, 0, 0)
        } catch (error: IllegalArgumentException) {
            return Result(State.UNSUPPORTED, 0, 0)
        } catch (error: IOException) {
            return Result(
                if (cancelled) {
                    State.CANCELLED
                } else if (System.nanoTime() >=
                    deadline
                ) {
                    State.LIMIT
                } else {
                    State.NETWORK_ERROR
                },
                0,
                0
            )
        }
    }

    @Throws(IOException::class)
    private fun fetch(url: HttpUrl, headers: Map<String, String>, limit: Int): Sample {
        var url: HttpUrl = url
        var headers: Map<String, String> = headers
        for (redirect: Int in 0..3) {
            if (cancelled) return Sample.Companion.failed(State.CANCELLED, 0)
            if (++requests > REQUESTS ||
                System.nanoTime() >= deadline
            ) {
                return Sample.Companion.failed(
                    State.LIMIT,
                    0
                )
            }
            val builder: Request.Builder = Request.Builder().url(url)
            headers.forEach { (key: String, value: String) ->
                if (!key.equals("Range", ignoreCase = true) && !key.equals(
                        "Host",
                        ignoreCase = true
                    ) && !key.equals(
                        "Content-Length",
                        ignoreCase = true
                    ) && !key.equals("Content-Type", ignoreCase = true)
                ) {
                    builder.header(key, value)
                }
            }
            builder.header("Range", "bytes=0-" + (limit - 1))
            val call: Call = client.newCall(builder.build())
            current = call
            call.timeout().timeout(
                max(1, min(TimeUnit.SECONDS.toNanos(8), deadline - System.nanoTime())),
                TimeUnit.NANOSECONDS
            )
            if (cancelled) call.cancel()
            try {
                call.execute().use { response ->
                    if (response.isRedirect) {
                        val location: String? = response.header("Location")
                        val next: HttpUrl? = if (location == null) null else url.resolve(location)
                        if (next == null || !allowed(next) ||
                            ("https" != next.scheme)
                        ) {
                            return Sample.Companion.failed(
                                State.UNSUPPORTED,
                                response.code
                            )
                        }
                        headers = forwarded(url, next, headers)
                        url = next
                        continue
                    }
                    if (!response.isSuccessful) {
                        return Sample.Companion.failed(
                            State.HTTP_ERROR,
                            response.code
                        )
                    }
                    return OpenDriveStreamCheck.Sample(
                        url,
                        headers,
                        response.code,
                        readAtMost(response.body.byteStream(), limit),
                        response.header("Content-Type", "")!!.lowercase(),
                        null
                    )
                }
            } finally {
                current = null
            }
        }
        return Sample.Companion.failed(State.LIMIT, 0)
    }

    override fun close() {
        cancelled = true
        val call: Call? = current
        if (call != null) call.cancel()
    }

    @JvmRecord
    private data class Sample(
        val url: HttpUrl?,
        val headers: Map<String, String>?,
        val status: Int,
        val bytes: ByteArray?,
        val type: String?,
        val failure: Result?
    ) {
        companion object {
            @JvmStatic
            fun failed(state: State?, status: Int): Sample = Sample(
                null,
                emptyMap(),
                status,
                ByteArray(0),
                "",
                Result(state, status, 0)
            )
        }
    }

    companion object {
        private const val SAMPLE = 1024
        private val PLAYLIST = 256 * 1024
        private const val REQUESTS = 8

        @Throws(IOException::class)
        @JvmStatic
        private fun readAtMost(stream: InputStream, limit: Int): ByteArray {
            val bytes: ByteArray = ByteArray(limit)
            var offset: Int = 0
            while (offset < limit) {
                val count: Int = stream.read(bytes, offset, limit - offset)
                if (count < 0) break
                if (count == 0) {
                    val value: Int = stream.read()
                    if (value < 0) break
                    bytes[offset++] = value.toByte()
                } else {
                    offset += count
                }
            }
            return if (offset == limit) bytes else bytes.copyOf(offset)
        }

        @JvmStatic
        private fun allowed(url: HttpUrl): Boolean =
            url.username.isEmpty() && url.password.isEmpty() &&
                (url.isHttps || url.host == "127.0.0.1" || url.host == "localhost")

        @JvmStatic
        fun forwarded(
            from: HttpUrl,
            to: HttpUrl,
            headers: Map<String, String>
        ): Map<String, String> {
            if (from.scheme == to.scheme && from.host == to.host &&
                from.port == to.port
            ) {
                return headers
            }
            // Cross-origin requests get only presentation headers; never explicit credentials.
            val result: MutableMap<String, String> = LinkedHashMap<String, String>()
            headers.forEach { (key: String, value: String) ->
                if (key.equals("User-Agent", ignoreCase = true) || key.equals(
                        "Accept",
                        ignoreCase = true
                    )
                ) {
                    result.put(key, value)
                }
            }
            return result
        }
    }
}
