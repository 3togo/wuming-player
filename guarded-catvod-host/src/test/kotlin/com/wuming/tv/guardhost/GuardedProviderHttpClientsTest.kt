package com.wuming.tv.guardhost

import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class GuardedProviderHttpClientsTest {
    @After fun reset() = GuardedProviderHttpClients.configure("")

    @Test fun boundsDiscoveryAndRestoresPlaybackClient() {
        val original = OkHttpClient()
        GuardedProviderHttpClients.configure("com.github.catvod.spider.JPJ")
        val bounded = GuardedProviderHttpClients.client(original)
        assertEquals(4000, bounded.callTimeoutMillis)
        assertSame(bounded, GuardedProviderHttpClients.client(original))
        assertSame(original.cookieJar, bounded.cookieJar)
        assertSame(original.dispatcher, bounded.dispatcher)
        assertEquals(0, original.callTimeoutMillis)
        GuardedProviderHttpClients.initialized()
        assertSame(original, GuardedProviderHttpClients.client(original))
    }

    @Test fun preservesStricterDeadline() {
        GuardedProviderHttpClients.configure("com.github.catvod.spider.JPJ")
        val original = OkHttpClient.Builder().callTimeout(1, TimeUnit.SECONDS).build()
        assertEquals(1000, GuardedProviderHttpClients.client(original).callTimeoutMillis)
    }

    @Test fun stalledMirrorCannotPreventNextRequest() {
        GuardedProviderHttpClients.configure("com.github.catvod.spider.JPJ")
        val original = OkHttpClient.Builder().callTimeout(100, TimeUnit.MILLISECONDS).build()
        val client = GuardedProviderHttpClients.client(original)
        java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            val request = okhttp3.Request.Builder()
                .url("http://127.0.0.1:${server.localPort}/config").build()
            try {
                client.newCall(request).execute().close()
                fail("A mirror that never responds must time out")
            } catch (expected: java.io.IOException) {
                assertFalse(client.dispatcher.runningCallsCount() > 0)
            }
        }
        java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            val responder = Thread {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray()
                    )
                }
            }
            responder.start()
            try {
                val request = okhttp3.Request.Builder()
                    .url("http://127.0.0.1:${server.localPort}/config").build()
                client.newCall(request).execute().use { assertEquals("{}", it.body!!.string()) }
            } finally { responder.join(2000) }
        }
    }

    @Test fun leavesOtherProvidersUnchanged() {
        val original = OkHttpClient()
        GuardedProviderHttpClients.configure("com.github.catvod.spider.Other")
        assertSame(original, GuardedProviderHttpClients.client(original))
    }
}
