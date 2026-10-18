package com.wuming.tv.desktop.player

import com.wuming.tv.core.base.HttpTokens
import com.wuming.tv.core.base.ByteRanges
import com.wuming.tv.core.source.HttpConnectionPolicy
import com.wuming.tv.core.runtime.js.EnvironmentProxySelector
import com.wuming.tv.core.playback.HlsHeaderRelayPolicy
import com.wuming.tv.core.playback.HlsManifestDetector
import com.wuming.tv.desktop.net.DesktopHttpStatusException
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.io.IOException
import okhttp3.Call
import java.util.logging.Level
import java.util.logging.Logger
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal object DesktopPlaybackHttpClient {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(15))
        .callTimeout(Duration.ofSeconds(30))
        .proxySelector(EnvironmentProxySelector())
        .dns(Ipv4WhenAvailableDns())
        .cookieJar(PlaybackCookieJar())
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val url = request.url
            chain.proceed(if (HttpConnectionPolicy.shouldClose(url.scheme, url.host)) {
                request.newBuilder().header("Connection", "close").build()
            } else request)
        }
        .build()
}

/** Avoids unroutable IPv6 CDN answers while preserving access to IPv6-only hosts. */
internal class Ipv4WhenAvailableDns(
    private val delegate: Dns = Dns.SYSTEM,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        val ipv4 = addresses.filterIsInstance<Inet4Address>()
        return ipv4.ifEmpty { addresses }
    }
}

internal class PlaybackCookieJar : CookieJar {
    private val cookies = CopyOnWriteArrayList<Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        this.cookies.removeIf { stored ->
            stored.expiresAt < now || cookies.any { incoming ->
                stored.name == incoming.name && stored.domain == incoming.domain && stored.path == incoming.path
            }
        }
        this.cookies += cookies.filter { it.expiresAt >= now }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookies.removeIf { it.expiresAt < now }
        return cookies.filter { it.matches(url) }
    }
}

/**
 * Validates progressive media with a bounded ranged GET and retains the bytes for VLC's first read.
 * The loopback relay also carries provider headers on subsequent reads and seeks.
 */
internal class ProgressivePlaybackPreparer(
    private val client: OkHttpClient = DesktopPlaybackHttpClient.client,
    private val probeBytes: Int = DEFAULT_PROBE_BYTES,
) : PlaybackTargetPreparer {
    private val logger = Logger.getLogger("tvbox.player.progressive")

    override fun prepare(location: String, headers: Map<String, String>): PreparedPlaybackTarget {
        val uri = runCatching { URI(location) }.getOrNull()
            ?.takeIf { it.scheme.equals("http", true) || it.scheme.equals("https", true) }
            ?: return PreparedPlaybackTarget(location)
        // Signed cloud-drive downloads commonly have extensionless URLs. VLC can forward UA and
        // Referer itself, but not Cookie or other provider headers, so those URLs must use the
        // loopback relay even when the path does not reveal the media format.
        if (!isProgressiveMedia(uri) && !requiresHeaderRelay(headers)) return PreparedPlaybackTarget(location)

        val request = request(location, headers, "bytes=0-${probeBytes - 1}")
        val probe = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw progressiveHttpFailure(response)
            val body = response.body
            val prefix = body.byteStream().readNBytes(probeBytes)
            if (prefix.isEmpty()) {
                throw PlaybackPreparationException("Media server returned an empty progressive response.")
            }
            if (HlsManifestDetector.looksLikeErrorDocument(prefix)) {
                throw PlaybackPreparationException(
                    "Media URL returned an error document instead of progressive media. " +
                        "Reload details or choose another playback source.",
                )
            }
            val contentRange = parseContentRange(response.header("Content-Range"))
            ProgressiveProbe(
                location = response.request.url.toString(),
                prefix = prefix,
                contentType = response.header("Content-Type") ?: "application/octet-stream",
                totalLength = contentRange?.total ?: body.contentLength().takeIf { response.code == 200 && it >= 0 },
                rangeSupported = response.code == 206 || response.header("Accept-Ranges").equals("bytes", true),
            )
        }
        val relay = ProgressivePlaybackRelay(client, safeHeaders(headers), probe)
        logger.info(
            "progressive probe ready host=${uri.host} cachedBytes=${probe.prefix.size} " +
                "totalBytes=${probe.totalLength ?: "unknown"} ranges=${probe.rangeSupported}",
        )
        return PreparedPlaybackTarget(relay.location, relay)
    }

    private fun request(location: String, headers: Map<String, String>, range: String): Request =
        Request.Builder().url(location).apply {
            safeHeaders(headers).forEach { (name, value) -> header(name, value) }
            header("Range", range)
            header("Accept-Encoding", "identity")
        }.get().build()

    private fun progressiveHttpFailure(response: Response): PlaybackPreparationException {
        val preview = response.body.byteStream().readNBytes(ERROR_PREVIEW_BYTES)
            .toString(Charsets.UTF_8)
            .replace(Regex("[\\p{Cntrl}&&[^\\r\\n\\t]]"), "")
            .replace(Regex("\\s+"), " ").trim().take(240)
        val cause = DesktopHttpStatusException(response.code, preview)
        val guidance = when (response.code) {
            401, 403 -> "Reload details for a fresh signed link; if it still fails, choose another source or network route."
            404, 410 -> "The provider link is no longer available; reload details or choose another source."
            else -> "Retry later or choose another playback source."
        }
        return PlaybackPreparationException(
            "Media server rejected the progressive probe (HTTP ${response.code}). $guidance",
            cause,
        )
    }

    private fun isProgressiveMedia(uri: URI): Boolean {
        val path = uri.path.orEmpty().lowercase()
        return PROGRESSIVE_EXTENSIONS.any(path::endsWith)
    }

    private fun requiresHeaderRelay(headers: Map<String, String>): Boolean =
        HlsHeaderRelayPolicy.requiresHeaderRelay(headers)

    private fun safeHeaders(values: Map<String, String>): Map<String, String> = values.filter { (name, value) ->
        HttpTokens.isToken(name) && name.lowercase() !in FORBIDDEN_HEADERS &&
            HttpTokens.isHeaderValue(value)
    }

    private companion object {
        const val DEFAULT_PROBE_BYTES = 64 * 1024
        const val ERROR_PREVIEW_BYTES = 1_024
        val FORBIDDEN_HEADERS = setOf("connection", "content-length", "host", "range", "transfer-encoding")
        val PROGRESSIVE_EXTENSIONS = setOf(
            ".mp4", ".m4v", ".mkv", ".webm", ".mov", ".avi", ".flv", ".ts",
            ".mp3", ".m4a", ".aac", ".ogg", ".opus", ".wav",
        )
    }
}

private data class ProgressiveProbe(
    val location: String,
    val prefix: ByteArray,
    val contentType: String,
    val totalLength: Long?,
    val rangeSupported: Boolean,
)

private data class ByteRange(val start: Long, val end: Long?)
private data class ContentRange(val start: Long, val end: Long, val total: Long?)

private class ProgressivePlaybackRelay(
    client: OkHttpClient,
    private val headers: Map<String, String>,
    private val probe: ProgressiveProbe,
) : AutoCloseable {
    private val logger = Logger.getLogger("tvbox.player.progressive")
    private val secret = UUID.randomUUID().toString().replace("-", "")
    private val executor = RelayExecutors.create("tvbox-progressive-relay")
    // A complete movie can keep its upstream response open for hours. Retain the client's
    // connection/read timeouts, but don't apply the probe's deadline to the whole stream.
    private val streamingClient = client.newBuilder().callTimeout(Duration.ZERO).build()
    private val activeCalls = mutableSetOf<Call>()
    private var closed = false
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/$secret/media") { exchange -> handle(exchange) }
        this.executor = this@ProgressivePlaybackRelay.executor
        start()
    }

    val location: String = "http://127.0.0.1:${server.address.port}/$secret/media"

    private fun handle(exchange: HttpExchange) {
        try {
            require(exchange.requestMethod == "GET" || exchange.requestMethod == "HEAD") {
                "Only GET and HEAD are supported"
            }
            if (exchange.requestMethod == "HEAD") {
                setCommonHeaders(exchange)
                probe.totalLength?.let { exchange.responseHeaders.set("Content-Length", it.toString()) }
                exchange.sendResponseHeaders(200, -1)
                return
            }
            val requested = parseRange(exchange.requestHeaders.getFirst("Range"))
            if (requested != null && requested.start < probe.prefix.size) {
                serveFromPrefix(exchange, requested)
            } else if (requested == null && probe.rangeSupported) {
                serveFromPrefix(exchange, ByteRange(0, probe.totalLength?.minus(1)), partial = false)
            } else {
                proxy(exchange, requested)
            }
        } catch (failure: Exception) {
            logger.log(Level.WARNING, "progressive relay failed error=${failure.message}", failure)
            runCatching { exchange.sendResponseHeaders(502, -1) }
        } finally {
            exchange.close()
        }
    }

    private fun serveFromPrefix(exchange: HttpExchange, requested: ByteRange, partial: Boolean = true) {
        val total = probe.totalLength
        val requestedEnd = requested.end ?: total?.minus(1)
        val cachedEnd = minOf(
            probe.prefix.lastIndex.toLong(),
            requestedEnd ?: probe.prefix.lastIndex.toLong(),
        )
        val cachedStart = requested.start.toInt()
        val tailStart = cachedEnd + 1
        val needsTail = requestedEnd == null || tailStart <= requestedEnd
        val opened = if (needsTail) openUpstream(ByteRange(tailStart, requestedEnd)) else null
        val upstream = opened?.response
        try {
            setCommonHeaders(exchange)
            val finalEnd = requestedEnd ?: upstream?.let { responseEnd(it) }
            if (partial && finalEnd != null && total != null) {
                exchange.responseHeaders.set("Content-Range", "bytes ${requested.start}-$finalEnd/$total")
            }
            val responseLength = finalEnd?.let { it - requested.start + 1 }
            exchange.sendResponseHeaders(if (partial) 206 else 200, responseLength ?: 0)
            exchange.responseBody.use { output ->
                output.write(probe.prefix, cachedStart, (cachedEnd - requested.start + 1).toInt())
                upstream?.body?.byteStream()?.use { input ->
                    if (upstream.code == 200) input.skipFully(tailStart)
                    copyLimited(input, output, requestedEnd?.let { it - tailStart + 1 })
                }
            }
        } finally {
            opened?.close()
        }
    }

    private fun proxy(exchange: HttpExchange, requested: ByteRange?) {
        val upstream = openUpstream(requested)
        upstream.use { opened ->
            val response = opened.response
            setCommonHeaders(exchange, response.header("Content-Type"))
            response.header("Content-Range")?.let { exchange.responseHeaders.set("Content-Range", it) }
            val length = response.body.contentLength()
            exchange.sendResponseHeaders(response.code, if (length >= 0) length else 0)
            response.body.byteStream().use { input -> exchange.responseBody.use { output -> input.copyTo(output) } }
        }
    }

    private fun openUpstream(range: ByteRange?): UpstreamResponse {
        val request = Request.Builder().url(probe.location).apply {
            headers.forEach { (name, value) -> header(name, value) }
            header("Accept-Encoding", "identity")
            range?.let { header("Range", "bytes=${it.start}-${it.end ?: ""}") }
        }.get().build()
        val call = streamingClient.newCall(request)
        synchronized(activeCalls) {
            if (closed) throw IOException("Relay closed")
            activeCalls.add(call)
        }
        try {
            val response = call.execute()
            if (response.isSuccessful) return UpstreamResponse(call, response)
            val code = response.code
            response.close()
            throw IllegalArgumentException("Upstream media request returned HTTP $code")
        } catch (error: Throwable) {
            synchronized(activeCalls) { activeCalls.remove(call) }
            call.cancel()
            throw error
        }
    }

    private inner class UpstreamResponse(val call: Call, val response: Response) : AutoCloseable {
        override fun close() {
            try {
                response.close()
            } finally {
                synchronized(activeCalls) { activeCalls.remove(call) }
            }
        }
    }

    private fun responseEnd(response: Response): Long? =
        parseContentRange(response.header("Content-Range"))?.end
            ?: response.body.contentLength().takeIf { it >= 0 }?.let { length ->
                if (response.code == 200) length - 1 else null
            }

    private fun setCommonHeaders(exchange: HttpExchange, contentType: String? = null) {
        exchange.responseHeaders.set("Content-Type", contentType ?: probe.contentType)
        exchange.responseHeaders.set("Cache-Control", "no-store")
        if (probe.rangeSupported) exchange.responseHeaders.set("Accept-Ranges", "bytes")
    }

    override fun close() {
        synchronized(activeCalls) {
            closed = true
            activeCalls.forEach(Call::cancel)
            activeCalls.clear()
        }
        server.stop(0)
        executor.shutdownNow()
        probe.prefix.fill(0)
        logger.info("progressive relay stopped")
    }
}

private fun parseRange(value: String?): ByteRange? =
    ByteRanges.parseRequest(value)?.let { ByteRange(it.start, it.endInclusive) }

private fun parseContentRange(value: String?): ContentRange? =
    ByteRanges.parseResponse(value)?.let { ContentRange(it.start, it.endInclusive, it.total) }

private fun java.io.InputStream.skipFully(bytes: Long) {
    var remaining = bytes
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) remaining -= skipped
        else if (read() < 0) break
        else remaining--
    }
}

private fun copyLimited(input: java.io.InputStream, output: java.io.OutputStream, maximum: Long?) {
    val buffer = ByteArray(8 * 1024)
    var remaining = maximum ?: Long.MAX_VALUE
    while (remaining > 0) {
        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        if (count < 0) break
        output.write(buffer, 0, count)
        remaining -= count
    }
}
