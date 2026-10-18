package com.wuming.tv.core.upnp

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.Proxy
import java.util.concurrent.Executors

class OkHttpStreamClientTest {

    @Test
    fun controlActionsDoNotReuseRendererConnections() {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val ports = mutableSetOf<Int>()
        server.createContext("/") { exchange ->
            try {
                assertEquals("close", exchange.requestHeaders.getFirst("Connection"))
                ports.add(exchange.remoteAddress.port)
                exchange.requestBody.readAllBytes()
                exchange.sendResponseHeaders(200, 2)
                exchange.responseBody.write("ok".toByteArray())
            } finally { exchange.close() }
        }
        server.start()
        val executor = Executors.newSingleThreadExecutor()
        val client = OkHttpStreamClient(OkHttpStreamClient.Configuration(executor), bypassProxy = true)
        try {
            repeat(2) {
                val message = org.fourthline.cling.model.message.StreamRequestMessage(
                    org.fourthline.cling.model.message.UpnpRequest.Method.POST, java.net.URI("http://127.0.0.1:${server.address.port}/"))
                message.setBody("control")
                val response = client.createCallable(message, client.createRequest(message)).call()
                assertEquals(200, response.operation.statusCode)
            }
            assertEquals(2, ports.size)
        } finally {
            client.stop()
            executor.shutdownNow()
            server.stop(0)
        }
    }

    private fun httpClient(client: OkHttpStreamClient): OkHttpClient {
        val field = OkHttpStreamClient::class.java.getDeclaredField("httpClient")
        field.isAccessible = true
        return field.get(client) as OkHttpClient
    }

    private fun maxBodyBytes(client: OkHttpStreamClient): Int {
        val field = OkHttpStreamClient::class.java.getDeclaredField("maxBodyBytes")
        field.isAccessible = true
        return field.get(client) as Int
    }

    @Test
    fun androidPathKeepsSystemProxyAndFullBody() {
        val client = OkHttpStreamClient(OkHttpStreamClient.Configuration(Executors.newSingleThreadExecutor()))
        // Default (Android) policy: system proxy selection, no response cap.
        assertNull(httpClient(client).proxy)
        assertEquals(Int.MAX_VALUE, maxBodyBytes(client))
    }

    @Test
    fun desktopPathBypassesProxyAndCapsBody() {
        val client = OkHttpStreamClient(
            OkHttpStreamClient.Configuration(Executors.newSingleThreadExecutor()),
            bypassProxy = true,
            maxBodyBytes = 1_048_576,
        )
        assertEquals(Proxy.NO_PROXY, httpClient(client).proxy)
        assertEquals(1_048_576, maxBodyBytes(client))
    }

    @Test
    fun timeoutIsProtocolSecondsPlusFive() {
        val client = OkHttpStreamClient(OkHttpStreamClient.Configuration(Executors.newSingleThreadExecutor()))
        val http = httpClient(client)
        assertEquals((client.getConfiguration().timeoutSeconds + 5) * 1000, http.connectTimeoutMillis)
        assertEquals(http.connectTimeoutMillis, http.readTimeoutMillis)
    }
}
