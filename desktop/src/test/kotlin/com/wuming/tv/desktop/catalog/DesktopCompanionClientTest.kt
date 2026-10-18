package com.wuming.tv.desktop.catalog

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopCompanionClientTest {

    private val token = "a".repeat(64)

    @Test
    fun pairsAuthorizesEncodesAndDecodesCatalogueRequests() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var rawSearchQuery = ""
        server.createContext("/pair") { respond(it, 200, token, "text/plain") }
        server.createContext("/desktop/v1/sources") { exchange ->
            assertEquals("Bearer $token", exchange.requestHeaders.getFirst("Authorization"))
            respond(exchange, 200, """[{"key":"demo","name":"Demo source"}]""")
        }
        server.createContext("/desktop/v1/search") { exchange ->
            rawSearchQuery = exchange.requestURI.rawQuery
            respond(exchange, 200, """[{"sourceKey":"demo","id":"42","name":"Demo film"}]""")
        }
        server.createContext("/desktop/v1/detail") { exchange ->
            respond(exchange, 200, """{"item":{"sourceKey":"demo","id":"42","name":"Demo film"},"lines":[]}""")
        }
        server.createContext("/desktop/v1/play") { exchange ->
            respond(exchange, 200, """{"supported":true,"url":"http://tv/relay.m3u8"}""")
        }
        server.start()
        try {
            val client = DesktopCompanionClient("http://127.0.0.1:${server.address.port}")

            assertEquals(token, client.pair())
            assertEquals("Demo source", client.sources().single().name)
            assertEquals("Demo film", client.search("demo", "食 神").single().name)
            assertTrue("query=%E9%A3%9F+%E7%A5%9E" in rawSearchQuery)
            assertEquals("42", client.detail("demo", "42").item.id)
            assertTrue(client.play("demo", "line", "episode").supported)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun reportsCompanionJsonErrors() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/desktop/v1/sources") {
            respond(it, 401, """{"error":"Pairing required"}""")
        }
        server.start()
        try {
            val client = DesktopCompanionClient("http://127.0.0.1:${server.address.port}", token)

            val error = runCatching { client.sources() }.exceptionOrNull()

            assertTrue(error is CompanionException)
            assertEquals("Pairing required", error?.message)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun storesPairingWithOwnerOnlyPermissions() {
        val directory = Files.createTempDirectory("tvbox-companion-settings-")
        val file = directory.resolve("connection.properties")
        try {
            val settings = CompanionSettings(file)

            settings.save(CompanionConnection("http://tv:9978", token))

            assertEquals(CompanionConnection("http://tv:9978", token), settings.load())
            runCatching { Files.getPosixFilePermissions(file) }.getOrNull()?.let { permissions ->
                assertFalse(permissions.any { it.name.startsWith("GROUP_") || it.name.startsWith("OTHERS_") })
            }
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        body: String,
        contentType: String = "application/json",
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", contentType)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
