package com.wuming.tv.core.runtime.guardspider

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.*
import java.net.*
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.*

/** Per-provider loopback bridge for CatVod's Any[] proxy response contract.  */
class GuardedSpiderProxyServer
@Throws(IOException::class)
constructor(handler: Handler) :
    AutoCloseable {
    fun interface Handler {
        @Throws(Exception::class)
        fun proxy(parameters: MutableMap<String?, String?>?): Array<Any?>?
    }

    private val server: HttpServer
    private val workers: ExecutorService
    private val path = "/" + UUID.randomUUID() + "/proxy"
    private val url: String

    init {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 8)
        url = "http://127.0.0.1:" + server.getAddress().getPort() + path
        workers = ThreadPoolExecutor(0, 8, 30, TimeUnit.SECONDS, SynchronousQueue(), { task ->
            val thread: Thread = Thread(task, "guarded-spider-proxy")
            thread.setDaemon(true)
            thread
        })
        server.setExecutor(workers)
        server.createContext("/", { exchange -> handle(exchange, handler) })
        server.start()
    }

    /** Only the known unbound CatVod loopback endpoint is rewritten; remote URLs are untouched.  */
    fun rewrite(value: String): String =
        value.replace(LEGACY.toString() + "?", url.toString() + "?")

    /** JSON may escape slashes or Unicode: rewrite decoded URL values, never serialized text.  */
    fun rewritePlaybackJson(json: String?): String? {
        // Auete escapes JSON slashes while Bili does not. Replacing serialized text left
        // Auete's port at -1 even though the same proxy worked for Bili. Decode first.
        try {
            val root: JsonElement = JsonParser.parseString(json)
            if (!root.isJsonObject()) return json
            for (key: String? in java.util.List.of("url", "playUrl", "danmaku")) {
                val value: JsonElement? = root.getAsJsonObject().get(key)
                if (value != null) root.getAsJsonObject().add(key, rewriteValue(value))
            }
            return root.toString()
        } catch (malformed: RuntimeException) {
            return json // The catalogue codec owns malformed-response diagnostics.
        }
    }

    private fun rewriteValue(value: JsonElement): JsonElement {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            return JsonPrimitive(rewrite(value.getAsString()))
        }
        if (value.isJsonArray()) {
            for (i: Int in 0..<value.getAsJsonArray().size()) {
                value.getAsJsonArray().set(i, rewriteValue(value.getAsJsonArray().get(i)))
            }
        } else if (value.isJsonObject()) {
            for (key: String? in ArrayList(value.getAsJsonObject().keySet())) {
                value.getAsJsonObject().add(key, rewriteValue(value.getAsJsonObject().get(key)))
            }
        }
        return value
    }

    @Throws(IOException::class)
    private fun handle(exchange: HttpExchange, handler: Handler) {
        try {
            if (!path.equals(exchange.getRequestURI().getPath())) {
                reject(exchange, 404)
                return
            }
            val method: String = exchange.getRequestMethod()
            if (!method.equals("GET") && !method.equals("HEAD")) {
                reject(exchange, 405)
                return
            }
            val query: String? = exchange.getRequestURI().getRawQuery()
            if (query == null || query.length > 16384) {
                reject(exchange, 400)
                return
            }
            val parameters: MutableMap<String?, String?> = HashMap()
            for (pair: String in java.util.regex.Pattern.compile("&").split(query)) {
                val parts = pair.split("=", limit = 2)
                parameters.put(
                    URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    if (parts.size == 2) URLDecoder.decode(parts[1], StandardCharsets.UTF_8) else ""
                )
            }
            for (header: String in java.util.List.of(
                "Range",
                "User-Agent",
                "Referer",
                "Origin",
                "Cookie"
            )) {
                val value: String? = exchange.getRequestHeaders().getFirst(header)
                if (value != null) {
                    parameters.putIfAbsent(header.lowercase(Locale.ROOT), value)
                    if (header.equals("Range")) parameters.put("Range", value)
                }
            }
            val response: Array<Any?>? = handler.proxy(parameters)
            if (response == null || response.size < 3 || (response[2] !is InputStream)) {
                reject(exchange, 502)
                return
            }
            (response[2] as InputStream).use { input ->
                val status: Int = (response[0] as Number).toInt()
                if (status < 200 || status > 599) throw IOException("Invalid provider proxy status")
                val mime: String = java.lang.String.valueOf(response[1])
                exchange.getResponseHeaders().set("Content-Type", mime)
                var length: Long = 0
                if (response.size > 3 && response[3] is MutableMap<*, *>) {
                    for (entry: Map.Entry<*, *> in (response[3] as Map<*, *>).entries) {
                        val name: String = java.lang.String.valueOf(entry.key)
                        val value: String? = java.lang.String.valueOf(entry.value)
                        if (name.equals("Content-Length", ignoreCase = true)) {
                            try {
                                length = Math.max(0, java.lang.Long.parseLong(value))
                            } catch (ignored: NumberFormatException) {
                            }
                        } else if (!HOP_HEADERS.contains(name.lowercase(Locale.ROOT))) {
                            exchange.getResponseHeaders().set(name, value)
                        }
                    }
                }
                // DASH/HLS can contain further provider-local requests. Keep them on this capability.
                val lower: String = mime.lowercase(Locale.ROOT)
                var manifest: Boolean = lower.contains("dash+xml") || lower.contains("mpegurl")
                val encoding: String? = exchange.getResponseHeaders().getFirst("Content-Encoding")
                manifest =
                    manifest and
                    (encoding == null || encoding.equals("identity", ignoreCase = true))
                var rewritten: ByteArray? = null
                if (manifest) {
                    val bytes: ByteArray = input.readNBytes(4 * 1024 * 1024 + 1)
                    if (bytes.size >
                        4 * 1024 * 1024
                    ) {
                        throw IOException("Provider manifest too large")
                    }
                    rewritten = rewrite(
                        String(
                            bytes,
                            StandardCharsets.UTF_8
                        )
                    ).toByteArray(StandardCharsets.UTF_8)
                    length = rewritten!!.size.toLong()
                    exchange.getResponseHeaders().remove("ETag")
                    exchange.getResponseHeaders().remove("Content-MD5")
                }
                if (method.equals("HEAD")) {
                    if (length > 0) {
                        exchange.getResponseHeaders()
                            .set("Content-Length", length.toString())
                    }
                    exchange.sendResponseHeaders(status, -1)
                } else {
                    exchange.sendResponseHeaders(
                        status,
                        if (status == 204 || status == 304) -1 else length
                    )
                    if (status != 204 && status != 304) {
                        if (rewritten != null) {
                            exchange.getResponseBody().write(rewritten)
                        } else {
                            input.transferTo(exchange.getResponseBody())
                        }
                    }
                }
            }
        } catch (failure: Exception) {
            // Provider diagnostics can include signed URLs or cookies; keep HTTP errors generic.
            java.util.logging.Logger.getLogger("tvbox.guarded.proxy")
                .warning("Provider proxy failed type=" + failure.javaClass.getSimpleName())
            try {
                reject(exchange, 502)
            } catch (ignored: IOException) {
            }
        } finally {
            exchange.close()
        }
    }

    override fun close() {
        server.stop(0)
        workers.shutdownNow()
    }

    companion object {
        private val LEGACY = "http://127.0.0.1:-1/proxy"
        private val HOP_HEADERS: MutableSet<String?> = java.util.Set.of(
            "connection",
            "transfer-encoding",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "upgrade",
            "content-length"
        )

        @Throws(IOException::class)
        private fun reject(exchange: HttpExchange, status: Int) {
            exchange.sendResponseHeaders(status, -1)
        }
    }
}
