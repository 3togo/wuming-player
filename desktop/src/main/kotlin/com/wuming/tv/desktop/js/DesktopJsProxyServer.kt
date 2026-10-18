package com.wuming.tv.desktop.js

import com.wuming.tv.core.runtime.js.JsProxyResponse
import com.wuming.tv.core.runtime.js.JsProxyUrlFactory
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Loopback-only capability server for CatVod JavaScript `proxy` callbacks. */
class DesktopJsProxyServer : AutoCloseable {
    private val secret = UUID.randomUUID().toString()
    private val routes = ConcurrentHashMap<String, Route>()
    private val executor = Executors.newCachedThreadPool { task ->
        Thread(task, "tvbox-js-proxy").apply { isDaemon = true }
    }
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/$secret/", ::serve)
        this.executor = this@DesktopJsProxyServer.executor
        start()
    }

    fun register(sourceKey: String): Registration {
        require(sourceKey.isNotBlank()) { "Provider key is required" }
        return Registration(sourceKey)
    }

    inner class Registration internal constructor(private val sourceKey: String) : JsProxyUrlFactory, AutoCloseable {
        @Volatile
        private var handler: ProxyHandler? = null

        fun attach(value: ProxyHandler) {
            check(handler == null) { "JavaScript proxy handler is already attached" }
            handler = value
        }

        override fun create(path: String, headers: Map<String, String>): String {
            check(handler != null) { "JavaScript proxy handler is not attached" }
            require(path.length <= MAX_PATH_LENGTH) { "JavaScript proxy path is too long" }
            prune()
            require(routes.size < MAX_ROUTES) { "JavaScript proxy capability quota exceeded" }
            val token = UUID.randomUUID().toString()
            val segments = path.split('/').filter(String::isNotEmpty)
            require(segments.isNotEmpty() && segments.size <= MAX_SEGMENTS) { "JavaScript proxy path is invalid" }
            routes[token] = Route(sourceKey, segments, safeHeaders(headers), Instant.now().toEpochMilli()) { a, b, c ->
                handler?.invoke(a, b, c) ?: throw IllegalStateException("JavaScript proxy is closed")
            }
            val encoded = segments.joinToString("/") { URLEncoder.encode(it, StandardCharsets.UTF_8).replace("+", "%20") }
            return "http://127.0.0.1:${server.address.port}/$secret/$token/$encoded"
        }

        override fun close() {
            handler = null
            routes.entries.removeIf { it.value.sourceKey == sourceKey }
        }
    }

    fun interface ProxyHandler {
        fun invoke(segments: List<String>, headers: Map<String, String>, requestHeaders: Map<String, String>): JsProxyResponse
    }

    private fun serve(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "GET" && exchange.requestMethod != "HEAD") {
                respondError(exchange, 405, "Method not allowed")
                return
            }
            val parts = exchange.requestURI.rawPath.removePrefix("/$secret/").split('/')
            val token = parts.firstOrNull().orEmpty()
            val route = routes[token]
            if (route == null || expired(route)) {
                routes.remove(token)
                respondError(exchange, 404, "Proxy capability expired")
                return
            }
            val urlSegments = parts.drop(1).map { URLDecoder.decode(it, StandardCharsets.UTF_8) }
            if (urlSegments != route.segments) {
                respondError(exchange, 403, "Proxy capability mismatch")
                return
            }
            val requestHeaders = exchange.requestHeaders.entries.associate { (name, values) ->
                name to values.joinToString(", ").take(8_192)
            }
            val response = route.handler.invoke(route.segments, route.headers, requestHeaders)
            response.headers.forEach { (name, value) ->
                if (name.length <= 128 && HTTP_HEADER_NAME.matches(name) &&
                    value.none { it == '\r' || it == '\n' } &&
                    name.lowercase() !in FORBIDDEN_RESPONSE_HEADERS
                ) {
                    exchange.responseHeaders.set(name, value.take(8_192))
                }
            }
            if (exchange.requestMethod == "HEAD") {
                exchange.sendResponseHeaders(response.status, -1)
                exchange.close()
            } else {
                exchange.sendResponseHeaders(response.status, response.body.size.toLong())
                exchange.responseBody.use { it.write(response.body) }
            }
        } catch (_: Throwable) {
            runCatching { respondError(exchange, 502, "JavaScript proxy failed") }
        }
    }

    private fun respondError(exchange: HttpExchange, status: Int, message: String) {
        val body = message.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
        if (exchange.requestMethod == "HEAD") {
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        } else {
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
    }

    private fun prune() {
        routes.entries.removeIf { expired(it.value) }
        if (routes.size >= MAX_ROUTES) {
            routes.entries.sortedBy { it.value.createdAt }.take(routes.size - MAX_ROUTES + 1)
                .forEach { routes.remove(it.key) }
        }
    }

    private fun expired(route: Route): Boolean = Instant.now().toEpochMilli() - route.createdAt > ROUTE_TTL_MILLIS

    private fun safeHeaders(headers: Map<String, String>): Map<String, String> = headers.entries.take(64)
        .associate { it.key.take(128) to it.value.take(8_192) }

    override fun close() {
        routes.clear()
        server.stop(0)
        executor.shutdownNow()
    }

    private data class Route(
        val sourceKey: String,
        val segments: List<String>,
        val headers: Map<String, String>,
        val createdAt: Long,
        val handler: ProxyHandler,
    )

    private companion object {
        const val MAX_ROUTES = 512
        const val MAX_PATH_LENGTH = 16 * 1024
        const val MAX_SEGMENTS = 32
        const val ROUTE_TTL_MILLIS = 6 * 60 * 60 * 1000L
        val HTTP_HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        val FORBIDDEN_RESPONSE_HEADERS = setOf(
            "connection", "content-length", "date", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade",
        )
    }
}
