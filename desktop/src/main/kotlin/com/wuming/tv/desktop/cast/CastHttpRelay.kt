package com.wuming.tv.desktop.cast

import com.sun.net.httpserver.HttpServer
import com.wuming.tv.core.playback.HlsManifestDetector
import com.wuming.tv.core.playback.HlsReferenceRewriter
import com.wuming.tv.core.playback.relay.LanRelayProtocol
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Cast-scoped media and HLS transport; only the chosen receiver can fetch its capability URLs. */
internal class CastHttpRelay(
    upstream: String,
    headers: Map<String, String>,
    receiverHost: String,
    client: OkHttpClient,
    mediaFormat: String = "",
) : AutoCloseable {
    private val origin = LanRelayProtocol.upstream(upstream)
    private val sourceHeaders = headers.toMap()
    private val forwardedHeaderNames = LanRelayProtocol.scopedHeaders(sourceHeaders, origin, origin).keys
    // Reapply origin scoping at every redirect, including redirects from HLS segments and keys.
    private val streamingClient = client.newBuilder().callTimeout(java.time.Duration.ZERO)
        .addNetworkInterceptor { chain ->
            val request = chain.request().newBuilder()
            forwardedHeaderNames.forEach(request::removeHeader)
            LanRelayProtocol.scopedHeaders(sourceHeaders, origin, chain.request().url.toUri())
                .forEach { (key, value) -> request.header(key, value) }
            chain.proceed(request.build())
        }.build()
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val receiver = InetAddress.getByName(receiverHost)
    private val local = DatagramSocket().use { it.connect(receiver, 1900); it.localAddress }
    private val server = HttpServer.create(InetSocketAddress(local, 0), 16)
    private val workers = Executors.newFixedThreadPool(4) { Thread(it, "tvbox-cast-http").apply { isDaemon = true } }
    private val root = "/media/${UUID.randomUUID()}/"
    private val address = "http://${if (local.hostAddress.contains(':')) "[${local.hostAddress}]" else local.hostAddress}:${server.address.port}"
    private data class Resource(val upstream: String, val manifest: Boolean)
    private val resources = ConcurrentHashMap<String, Resource>()
    private val paths = mutableMapOf<Resource, String>()
    val contentType: String = when {
        com.wuming.tv.core.playback.PlaybackTransportPlan.inferKind(upstream, mediaFormat, mediaFormat.equals("hls", true)) ==
            com.wuming.tv.core.playback.PlaybackTransportPlan.Kind.HLS -> "application/vnd.apple.mpegurl"
        origin.path.orEmpty().endsWith(".mp4", true) -> "video/mp4"
        else -> "video/*"
    }
    val url: String = reference(upstream, contentType == "application/vnd.apple.mpegurl")

    @Synchronized private fun reference(location: String, manifest: Boolean): String {
        LanRelayProtocol.upstream(location)
        val resource = Resource(location, manifest)
        val path = paths.getOrPut(resource) {
            require(paths.size < MAX_RESOURCES) { "Cast playlist contains too many resources" }
            val extension = if (manifest) "m3u8" else URI.create(location).path.orEmpty()
                .substringAfterLast('.', "").lowercase().takeIf { it in MEDIA_EXTENSIONS } ?: "media"
            "$root${paths.size}.$extension".also { resources[it] = resource }
        }
        return address + path
    }

    init {
        // Validate before opening the LAN endpoint, even if the receiver has not fetched anything.
        LanRelayProtocol.scopedHeaders(sourceHeaders, origin, origin)
        server.executor = workers
        server.createContext("/") { exchange ->
            try {
                val resource = resources[exchange.requestURI.rawPath]
                if (exchange.remoteAddress.address != receiver || resource == null) {
                    exchange.sendResponseHeaders(404, -1)
                    return@createContext
                }
                val head = exchange.requestMethod == "HEAD"
                if (!head && exchange.requestMethod != "GET") {
                    exchange.responseHeaders.set("Allow", "GET, HEAD")
                    exchange.sendResponseHeaders(405, -1)
                    return@createContext
                }
                exchange.responseHeaders.set("transferMode.dlna.org", "Streaming")
                val request = Request.Builder().url(resource.upstream).apply {
                    if (head) head()
                    exchange.requestHeaders.getFirst("Range")?.let { header("Range", it) }
                }.build()
                val call = streamingClient.newCall(request)
                calls.add(call)
                try {
                    call.execute().use { response ->
                        val source = response.body.source()
                        val type = response.header("Content-Type").orEmpty()
                        val manifest = response.isSuccessful && (resource.manifest || type.contains("mpegurl", true) ||
                            (!head && source.request(7).let {
                                HlsManifestDetector.isManifest(source.buffer.clone().readByteArray(minOf(source.buffer.size, 16)))
                            }))
                        if (manifest && !head) {
                            val bytes = source.inputStream().readNBytes(MAX_MANIFEST_BYTES + 1)
                            require(bytes.size <= MAX_MANIFEST_BYTES) { "Cast playlist exceeds size limit" }
                            require(HlsManifestDetector.isManifest(bytes)) { "Media host returned an invalid playlist" }
                            val base = response.request.url.toUri()
                            val rewritten = HlsReferenceRewriter.rewrite(bytes.toString(Charsets.UTF_8),
                                { value -> LanRelayProtocol.upstream(base.resolve(value).toASCIIString()).toASCIIString() },
                                { value, childManifest -> reference(value, childManifest) }).toByteArray()
                            exchange.responseHeaders.set("Content-Type", "application/vnd.apple.mpegurl")
                            exchange.responseHeaders.set("Cache-Control", "no-store")
                            exchange.sendResponseHeaders(200, rewritten.size.toLong())
                            exchange.responseBody.write(rewritten)
                        } else {
                            val length = if (manifest) -1 else com.wuming.tv.core.playback.CastRelayTransfer.responseLength(
                                head, response.body.contentLength(), response.header("Content-Length"))
                            for (name in listOf("Content-Type", "Content-Range", "Accept-Ranges")) {
                                response.header(name)?.let { exchange.responseHeaders.set(name, it) }
                            }
                            if (length >= 0) exchange.responseHeaders.set("Content-Length", length.toString())
                            exchange.sendResponseHeaders(response.code, if (head || length == 0L) -1 else if (length > 0) length else 0)
                            if (!head) source.inputStream().use { it.copyTo(exchange.responseBody, 65536) }
                        }
                    }
                } finally { calls.remove(call) }
            } catch (_: Exception) {
                runCatching { exchange.sendResponseHeaders(502, -1) }
            } finally { exchange.close() }
        }
        server.start()
    }

    override fun close() {
        server.stop(0)
        calls.forEach { it.cancel() }
        workers.shutdownNow()
    }

    private companion object {
        val MEDIA_EXTENSIONS = setOf("mp4", "ts", "m4s", "aac", "mp3", "webm", "mkv")
        const val MAX_RESOURCES = 16384
        const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024
    }
}
