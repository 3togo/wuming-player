package com.wuming.tv.desktop.cast

import com.sun.net.httpserver.HttpServer
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** One cast-scoped HTTP transport port; only the selected receiver can fetch its capability URL. */
internal class CastHttpRelay(
    upstream: String,
    headers: Map<String, String>,
    receiverHost: String,
    client: OkHttpClient,
) : AutoCloseable {
    // A media response can last hours; the shared API client has a 30-second call budget.
    private val streamingClient = client.newBuilder().callTimeout(java.time.Duration.ZERO).build()
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val receiver = InetAddress.getByName(receiverHost)
    private val local = DatagramSocket().use { it.connect(receiver, 1900); it.localAddress }
    private val server = HttpServer.create(InetSocketAddress(local, 0), 16)
    private val workers = Executors.newFixedThreadPool(4) { Thread(it, "tvbox-cast-http").apply { isDaemon = true } }
    private val path = "/media/${UUID.randomUUID()}/stream.mp4"
    val url: String = "http://${if (local.hostAddress.contains(':')) "[${local.hostAddress}]" else local.hostAddress}:${server.address.port}$path"

    init {
        server.executor = workers
        server.createContext("/") { exchange ->
            try {
                if (exchange.remoteAddress.address != receiver || exchange.requestURI.rawPath != path) {
                    exchange.sendResponseHeaders(404, -1)
                    return@createContext
                }
                val head = exchange.requestMethod == "HEAD"
                if (!head && exchange.requestMethod != "GET") {
                    exchange.responseHeaders.set("Allow", "GET, HEAD")
                    exchange.sendResponseHeaders(405, -1)
                    return@createContext
                }
                val request = Request.Builder().url(upstream).apply {
                    if (head) head()
                    headers.forEach { (key, value) -> header(key, value) }
                    exchange.requestHeaders.getFirst("Range")?.let { header("Range", it) }
                }.build()
                val call = streamingClient.newCall(request)
                calls.add(call)
                try {
                    call.execute().use { response ->
                        val length = com.wuming.tv.core.playback.CastRelayTransfer.responseLength(
                            head, response.body.contentLength(), response.header("Content-Length"))
                        for (name in listOf("Content-Type", "Content-Range", "Accept-Ranges")) {
                            response.header(name)?.let { exchange.responseHeaders.set(name, it) }
                        }
                        exchange.responseHeaders.set("transferMode.dlna.org", "Streaming")
                        if (length >= 0) exchange.responseHeaders.set("Content-Length", length.toString())
                        exchange.sendResponseHeaders(response.code, if (head || length == 0L) -1 else if (length > 0) length else 0)
                        if (!head) response.body.byteStream().use { it.copyTo(exchange.responseBody, 65536) }
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
}
