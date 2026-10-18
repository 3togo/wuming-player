package com.wuming.tv.desktop.player

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Collections
import java.time.Duration
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressivePlaybackRelayTest {

    @Test
    fun continuousPlaybackOutlivesTheProbeRequestDeadline() {
        val media = ByteArray(128 * 1024) { 7 }
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/movie.mp4") { exchange ->
                val start = exchange.requestHeaders.getFirst("Range").substringAfter("bytes=")
                    .substringBefore('-').toInt()
                val end = if (start == 0) 1023 else media.lastIndex
                exchange.responseHeaders.set("Content-Range", "bytes $start-$end/${media.size}")
                exchange.sendResponseHeaders(206, (end - start + 1).toLong())
                exchange.responseBody.use { output ->
                    if (start > 0) {
                        output.write(media, start, 1024)
                        output.flush()
                        // The response keeps flowing beyond the original short request deadline.
                        Thread.sleep(500)
                        output.write(media, start + 1024, end - start + 1 - 1024)
                    } else output.write(media, start, end - start + 1)
                }
            }
            start()
        }
        val client = OkHttpClient.Builder().callTimeout(Duration.ofMillis(250)).build()
        val prepared = ProgressivePlaybackPreparer(client, probeBytes = 1024)
            .prepare("http://127.0.0.1:${upstream.address.port}/movie.mp4", emptyMap())
        try {
            val response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI(prepared.location)).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
            assertEquals(200, response.statusCode())
            assertArrayEquals(media, response.body())
        } finally {
            prepared.resource?.close()
            upstream.stop(0)
        }
    }

    @Test
    fun closingRelayCancelsAnUpstreamReadWithoutWaitingForItsTimeout() {
        val releaseResponse = CountDownLatch(1)
        val streamStarted = CountDownLatch(1)
        val streamingCallCancelled = CountDownLatch(1)
        val upstreamTornDown = CountDownLatch(1)
        val upstreamLength = 1024L * 1024L
        val streamingRange = "bytes=1024-"
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/movie.mp4") { exchange ->
                val start = exchange.requestHeaders.getFirst("Range").substringAfter("bytes=")
                    .substringBefore('-').toLong()
                val end = if (start == 0L) 1023L else upstreamLength - 1
                exchange.responseHeaders.set("Content-Range", "bytes $start-$end/$upstreamLength")
                exchange.sendResponseHeaders(206, end - start + 1)
                exchange.responseBody.use { output ->
                    if (start == 0L) output.write(ByteArray(1024) { 7 })
                    else {
                        output.write(ByteArray(1024) { 7 })
                        output.flush()
                        streamStarted.countDown()
                        // Keep streaming until the relay stops reading. Cancelling the call closes
                        // the socket, which fails the next write here; a call nobody cancels keeps
                        // streaming until the player's 20 second read timeout expires.
                        try {
                            while (!releaseResponse.await(25, TimeUnit.MILLISECONDS)) {
                                output.write(ByteArray(1024) { 7 })
                                output.flush()
                            }
                        } catch (tornDown: IOException) {
                            upstreamTornDown.countDown()
                        }
                    }
                }
            }
            start()
        }
        val client = OkHttpClient.Builder().readTimeout(Duration.ofSeconds(20))
            .eventListener(object : EventListener() {
                override fun canceled(call: Call) {
                    // The probe has already been consumed and closed, so only the call that is
                    // streaming the body can still be outstanding at this point.
                    if (call.request().header("Range").orEmpty().startsWith(streamingRange)) {
                        streamingCallCancelled.countDown()
                    }
                }
            }).build()
        val prepared = ProgressivePlaybackPreparer(client, probeBytes = 1024)
            .prepare("http://127.0.0.1:${upstream.address.port}/movie.mp4", emptyMap())
        val response = HttpClient.newHttpClient().sendAsync(
            HttpRequest.newBuilder(URI(prepared.location)).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        try {
            assertTrue(streamStarted.await(5, TimeUnit.SECONDS))
            prepared.resource?.close()
            assertTrue(
                "Closing the relay must cancel the call that is still streaming the media body",
                streamingCallCancelled.await(5, TimeUnit.SECONDS),
            )
            assertTrue(
                "The cancelled call must drop its upstream connection instead of waiting for the read timeout",
                upstreamTornDown.await(5, TimeUnit.SECONDS),
            )
        } finally {
            prepared.resource?.close()
            releaseResponse.countDown()
            response.cancel(true)
            upstream.stop(0)
        }
    }

    @Test
    fun playbackDnsUsesIpv4WhenHostAlsoPublishesIpv6() {
        val ipv6 = InetAddress.getByName("2001:db8::1")
        val ipv4 = InetAddress.getByName("192.0.2.10")
        val dns = Ipv4WhenAvailableDns(Dns { listOf(ipv6, ipv4) })

        assertEquals(listOf(ipv4), dns.lookup("media.example"))
    }

    @Test
    fun playbackDnsRetainsIpv6ForIpv6OnlyHost() {
        val ipv6 = InetAddress.getByName("2001:db8::1")
        val dns = Ipv4WhenAvailableDns(Dns { listOf(ipv6) })

        assertEquals(listOf(ipv6), dns.lookup("media.example"))
    }

    @Test
    fun cachesRangeProbeAndReusesItForVlcInitialRead() {
        val media = ByteArray(192 * 1024) { index -> (index % 251).toByte() }
        val ranges = Collections.synchronizedList(mutableListOf<String?>())
        val providerHeaders = Collections.synchronizedList(mutableListOf<String?>())
        val cookieHeaders = Collections.synchronizedList(mutableListOf<String?>())
        val upstream = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/movie.mp4") { exchange ->
                ranges += exchange.requestHeaders.getFirst("Range")
                providerHeaders += exchange.requestHeaders.getFirst("Referer")
                cookieHeaders += exchange.requestHeaders.getFirst("Cookie")
                val requested = exchange.requestHeaders.getFirst("Range")
                val start = requested?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                val requestedEnd = requested?.substringAfter('-')?.toIntOrNull()
                val end = minOf(requestedEnd ?: media.lastIndex, media.lastIndex)
                val body = media.copyOfRange(start, end + 1)
                exchange.responseHeaders.set("Content-Type", "video/mp4")
                exchange.responseHeaders.set("Accept-Ranges", "bytes")
                if (ranges.size == 1) exchange.responseHeaders.add("Set-Cookie", "media_session=ready; Path=/")
                if (requested != null) exchange.responseHeaders.set("Content-Range", "bytes $start-$end/${media.size}")
                exchange.sendResponseHeaders(if (requested == null) 200 else 206, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        val source = "http://127.0.0.1:${upstream.address.port}/movie.mp4"
        val playbackClient = OkHttpClient.Builder().cookieJar(PlaybackCookieJar()).build()
        val prepared = ProgressivePlaybackPreparer(playbackClient, probeBytes = 64 * 1024)
            .prepare(source, mapOf("Referer" to "https://provider.example/"))
        try {
            assertNotEquals(source, prepared.location)
            assertEquals(listOf("bytes=0-65535"), ranges.toList())

            val prefix = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI(prepared.location)).header("Range", "bytes=0-31").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
            assertEquals(206, prefix.statusCode())
            assertArrayEquals(media.copyOfRange(0, 32), prefix.body())
            assertEquals(1, ranges.size)

            val complete = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI(prepared.location)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
            assertEquals(200, complete.statusCode())
            assertArrayEquals(media, complete.body())
            assertEquals(listOf("bytes=0-65535", "bytes=65536-196607"), ranges.toList())
            assertTrue(providerHeaders.all { it == "https://provider.example/" })
            assertEquals(listOf(null, "media_session=ready"), cookieHeaders.toList())
        } finally {
            prepared.resource?.close()
            upstream.stop(0)
        }
    }

    @Test
    fun rejectsBrokenProgressiveLinkBeforePlayerLaunch() {
        val upstream = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/broken.mp4") { exchange ->
                val body = "<html><h1>403 Forbidden</h1></html>".toByteArray()
                exchange.sendResponseHeaders(403, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            val failure = assertThrows(PlaybackPreparationException::class.java) {
                ProgressivePlaybackPreparer(OkHttpClient()).prepare(
                    "http://127.0.0.1:${upstream.address.port}/broken.mp4",
                    emptyMap(),
                )
            }

            assertTrue(failure.message.orEmpty().contains("HTTP 403"))
        } finally {
            upstream.stop(0)
        }
    }

    @Test
    fun extensionlessCloudDownloadUsesRelayToPreserveCookie() {
        val observedCookies = Collections.synchronizedList(mutableListOf<String?>())
        val media = ByteArray(96 * 1024) { index -> (index % 251).toByte() }
        val upstream = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/download") { exchange ->
                observedCookies += exchange.requestHeaders.getFirst("Cookie")
                if (observedCookies.last() != "cloud_session=approved") {
                    exchange.sendResponseHeaders(412, -1)
                    exchange.close()
                    return@createContext
                }
                val start = exchange.requestHeaders.getFirst("Range")
                    ?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                val body = media.copyOfRange(start, media.size)
                exchange.responseHeaders.set("Content-Type", "video/mp4")
                exchange.responseHeaders.set("Accept-Ranges", "bytes")
                exchange.responseHeaders.set("Content-Range", "bytes $start-${media.lastIndex}/${media.size}")
                exchange.sendResponseHeaders(206, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        val source = "http://127.0.0.1:${upstream.address.port}/download?signature=test"
        val prepared = ProgressivePlaybackPreparer(OkHttpClient(), probeBytes = 1024)
            .prepare(source, mapOf("Cookie" to "cloud_session=approved"))
        try {
            assertNotEquals(source, prepared.location)
            val response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI(prepared.location)).header("Range", "bytes=0-31").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
            assertEquals(206, response.statusCode())
            assertArrayEquals(media.copyOfRange(0, 32), response.body())
            assertTrue(observedCookies.all { it == "cloud_session=approved" })
        } finally {
            prepared.resource?.close()
            upstream.stop(0)
        }
    }
}
