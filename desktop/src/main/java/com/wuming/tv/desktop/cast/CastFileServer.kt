package com.wuming.tv.desktop.cast

import com.wuming.tv.core.base.ByteRanges
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.io.RandomAccessFile
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Exposes only one selected file, through an unguessable URL, for the lifetime of a cast. */
class CastFileServer
    @Throws(IOException::class)
    constructor(
        input: Path,
        receiverHost: String,
    ) : AutoCloseable {
        private val server: HttpServer
        private val executor: ExecutorService
        private val url: String

        init {
            val file = input.toRealPath()
            if (!Files.isRegularFile(file) || !Files.isReadable(file)) throw IOException("Choose a readable video file")
            val local =
                DatagramSocket().use { route ->
                    route.connect(InetAddress.getByName(receiverHost), 1900)
                    route.localAddress
                }
            server = HttpServer.create(InetSocketAddress(local, 0), 16)
            executor =
                Executors.newFixedThreadPool(4) { runnable ->
                    Thread(runnable, "tvbox-cast-file").apply { isDaemon = true }
                }
            server.executor = executor
            val token = "/media/" + UUID.randomUUID()
            val host = local.hostAddress
            url = "http://" + (if (host.contains(":")) "[$host]" else host) + ":" + server.address.port + token
            server.createContext("/") { exchange ->
                try {
                    if (exchange.requestURI.rawPath != token) {
                        exchange.sendResponseHeaders(404, -1)
                        return@createContext
                    }
                    val method = exchange.requestMethod
                    if (method != "GET" && method != "HEAD") {
                        exchange.responseHeaders.set("Allow", "GET, HEAD")
                        exchange.sendResponseHeaders(405, -1)
                        return@createContext
                    }
                    val size = Files.size(file)
                    val range = exchange.requestHeaders.getFirst("Range")
                    val bounds =
                        try {
                            range(range, size)
                        } catch (error: IllegalArgumentException) {
                            exchange.responseHeaders.set("Content-Range", "bytes */$size")
                            exchange.sendResponseHeaders(416, -1)
                            return@createContext
                        }
                    var length = bounds[1] - bounds[0] + 1
                    val mime = Files.probeContentType(file)
                    exchange.responseHeaders.set("Content-Type", mime ?: "application/octet-stream")
                    exchange.responseHeaders.set("Accept-Ranges", "bytes")
                    exchange.responseHeaders.set("Content-Length", length.toString())
                    if (range != null) exchange.responseHeaders.set("Content-Range", "bytes ${bounds[0]}-${bounds[1]}/$size")
                    exchange.sendResponseHeaders(if (range == null) 200 else 206, if (method == "HEAD" || length == 0L) -1 else length)
                    if (method == "HEAD" || length == 0L) return@createContext
                    RandomAccessFile(file.toFile(), "r").use { source ->
                        source.seek(bounds[0])
                        val buffer = ByteArray(65536)
                        while (length > 0) {
                            val count = source.read(buffer, 0, minOf(length, buffer.size.toLong()).toInt())
                            if (count < 0) break
                            exchange.responseBody.write(buffer, 0, count)
                            length -= count
                        }
                    }
                } finally {
                    exchange.close()
                }
            }
            server.start()
        }

        fun url(): String = url

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }

        companion object {
            @JvmStatic
            fun range(
                header: String?,
                size: Long,
            ): LongArray {
                if (header == null) return longArrayOf(0, size - 1)
                if (size == 0L) throw IllegalArgumentException()
                val suffix = ByteRanges.parseSuffixRequest(header)
                if (suffix != null) {
                    if (suffix <= 0) throw IllegalArgumentException()
                    val start = maxOf(0, size - suffix)
                    if (start >= size) throw IllegalArgumentException()
                    return longArrayOf(start, size - 1)
                }
                val requested = ByteRanges.parseRequest(header) ?: throw IllegalArgumentException()
                val start = requested.start
                val end = minOf(size - 1, requested.endInclusive ?: (size - 1))
                if (start >= size || end < start) throw IllegalArgumentException()
                return longArrayOf(start, end)
            }
        }
    }
