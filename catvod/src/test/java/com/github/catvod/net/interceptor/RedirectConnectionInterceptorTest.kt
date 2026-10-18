package com.github.catvod.net.interceptor

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class RedirectConnectionInterceptorTest {
    @Test fun malformedRedirectPoisonsAReusedConnection() {
        withClient(false) { client, url, _ ->
            val error = assertThrows(IOException::class.java) {
                client.newCall(Request.Builder().url(url).build()).execute().close()
            }
            assertTrue(error.message.orEmpty().contains("Unexpected status line: <html>"))
        }
    }

    @Test fun resolverConnectionClosesAndRedirectStillLoadsPlaylist() {
        withClient(true) { client, url, server ->
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals("#EXTM3U\n", response.body.string())
            }
            assertEquals(3, server.requests.size)
            assertFalse(server.requests.first().any { it.equals("Connection: close", true) })
            assertTrue(server.requests.drop(1).all { it.any { header -> header.equals("Connection: close", true) } })
        }
    }

    private fun withClient(fixed: Boolean, test: (OkHttpClient, String, BrokenRedirectServer) -> Unit) {
        BrokenRedirectServer().use { server ->
            val builder = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
                .dns { listOf(InetAddress.getLoopbackAddress()) }
                .connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.SECONDS)
            if (fixed) builder.addNetworkInterceptor(RedirectConnectionInterceptor())
            val client = builder.build()
            try {
                val url = if (fixed) "http://entry.example:${server.port}/entry"
                    else "http://gslbmgsplive.miguvideo.com:${server.port}/redirect"
                test(client, url, server)
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdownNow()
            }
        }
    }

    /** Reproduce the observed Content-Length: 0 redirect followed by unframed HTML. */
    private class BrokenRedirectServer : Closeable {
        private val listener = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        private val sockets = CopyOnWriteArrayList<Socket>()
        val requests = CopyOnWriteArrayList<List<String>>()
        val port get() = listener.localPort
        private val worker = thread(isDaemon = true, name = "broken-redirect-fixture") {
            try {
                while (!listener.isClosed) {
                    val socket = listener.accept()
                    sockets.add(socket)
                    socket.use {
                        socket.soTimeout = 2000
                        val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        while (true) {
                            val first = input.readLine() ?: break
                            val headers = mutableListOf(first)
                            while (true) {
                                val line = input.readLine() ?: break
                                if (line.isEmpty()) break
                                headers.add(line)
                            }
                            requests.add(headers)
                            val reply = if (first.contains("/entry ")) {
                                "HTTP/1.1 302 Found\r\nLocation: http://gslbmgsplive.miguvideo.com:$port/redirect\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            } else if (first.contains("/redirect ")) {
                                "HTTP/1.1 302 Found\r\nLocation: /playlist\r\nContent-Length: 0\r\nConnection: keep-alive\r\n\r\n<html>\r\n"
                            } else {
                                "HTTP/1.1 200 OK\r\nContent-Length: 8\r\nConnection: close\r\n\r\n#EXTM3U\n"
                            }
                            socket.getOutputStream().write(reply.toByteArray(Charsets.US_ASCII))
                            socket.getOutputStream().flush()
                            if (!first.contains("/redirect ") || headers.any { it.equals("Connection: close", true) }) break
                        }
                    }
                }
            } catch (_: IOException) {
                // Closing the fixture interrupts accept/read after each test.
            }
        }

        override fun close() {
            listener.close()
            sockets.forEach { it.close() }
            worker.join(1000)
        }
    }
}
