package com.wuming.tv.desktop.server

import com.google.gson.Gson
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.Executors

/** Lightweight local HTTP API for the desktop client, mirroring the Android Nano server pattern. */
class DesktopHttpApi(
    private val port: Int = 0,
    private val statusProvider: () -> PlaybackStatus = { PlaybackStatus() },
    private val playControl: (String) -> Boolean = { false },
    private val seekControl: (Long) -> Boolean = { false },
) : AutoCloseable {

    private val secret: String = UUID.randomUUID().toString()
    private val server: HttpServer = HttpServer.create(
        InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0
    )
    private val gson = Gson()

    init {
        server.createContext("/api/v1/status", StatusHandler())
        server.createContext("/api/v1/play", PlayHandler())
        server.createContext("/api/v1/seek", SeekHandler())
        server.executor = Executors.newSingleThreadExecutor { r -> Thread(r, "desktop-api") }
    }

    fun start(): Int {
        server.start()
        return server.address.port
    }

    override fun close() {
        server.stop(0)
    }

    fun baseUrl(): String = "http://127.0.0.1:${server.address.port}"

    private fun authorize(exchange: HttpExchange): Boolean {
        val auth = exchange.requestHeaders.getFirst("Authorization") ?: return false
        return auth == "Bearer $secret"
    }

    private fun sendJson(exchange: HttpExchange, code: Int, body: Any) {
        val json = gson.toJson(body)
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(code, json.toByteArray().size.toLong())
        exchange.responseBody.use { it.write(json.toByteArray()) }
    }

    private fun sendError(exchange: HttpExchange, code: Int, message: String) {
        sendJson(exchange, code, mapOf("error" to message))
    }

    private inner class StatusHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            if (!authorize(exchange)) { sendError(exchange, 401, "Unauthorized"); return }
            if (exchange.requestMethod != "GET") { sendError(exchange, 405, "Method Not Allowed"); return }
            sendJson(exchange, 200, statusProvider())
        }
    }

    private inner class PlayHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            if (!authorize(exchange)) { sendError(exchange, 401, "Unauthorized"); return }
            if (exchange.requestMethod != "POST") { sendError(exchange, 405, "Method Not Allowed"); return }
            val body = exchange.requestBody.use { it.readBytes() }.toString(Charsets.UTF_8)
            val action = gson.fromJson(body, Map::class.java)?.get("action")?.toString() ?: ""
            val success = playControl(action)
            sendJson(exchange, 200, mapOf("success" to success))
        }
    }

    private inner class SeekHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            if (!authorize(exchange)) { sendError(exchange, 401, "Unauthorized"); return }
            if (exchange.requestMethod != "POST") { sendError(exchange, 405, "Method Not Allowed"); return }
            val body = exchange.requestBody.use { it.readBytes() }.toString(Charsets.UTF_8)
            val position = gson.fromJson(body, Map::class.java)?.get("position")?.toString()?.toLongOrNull() ?: 0L
            val success = seekControl(position)
            sendJson(exchange, 200, mapOf("success" to success))
        }
    }
}

data class PlaybackStatus(
    val playing: Boolean = false,
    val position: Long = 0,
    val duration: Long = 0,
    val title: String = "",
)