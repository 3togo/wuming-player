package com.wuming.tv.desktop.player

import com.wuming.tv.core.playback.HlsAdRule
import com.wuming.tv.desktop.net.DesktopHttpStatusException
import java.net.ConnectException
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsCompatibilityRelayTest {

    @Test
    fun reportsSourceIpDenialBeforeLaunchingVlc() {
        val failure = assertThrows(PlaybackPreparationException::class.java) {
            HlsCompatibilityPreparer(
                fetch = { _, _ ->
                    throw IllegalArgumentException(
                        "Resource server returned HTTP 403. Response: the source ip is forbidden",
                    )
                },
            ).prepare(
                "https://media.example/episode.m3u8?sign=fresh",
                mapOf("Origin" to "https://provider.example"),
            )
        }

        assertTrue(failure.message.orEmpty().contains("Switch proxy route"))
    }

    @Test
    fun reportsGenericCdnDenialBeforeLaunchingVlcInsteadOfCreatingBrokenRelay() {
        val failure = assertThrows(PlaybackPreparationException::class.java) {
            HlsCompatibilityPreparer(
                fetch = { _, _ -> throw DesktopHttpStatusException(403, "403 Forbidden") },
            ).prepare(
                "https://media.example/episode.m3u8?auth_key=fresh",
                mapOf("Origin" to "https://provider.example"),
            )
        }

        assertTrue(failure.message.orEmpty().contains("HTTP 403"))
        assertTrue(failure.message.orEmpty().contains("fresh signed link"))
    }

    @Test
    fun rejectsHtmlDisguisedAsSuccessfulHlsResponse() {
        val failure = assertThrows(PlaybackPreparationException::class.java) {
            HlsCompatibilityPreparer(
                fetch = { _, _ -> "<html><h1>403 Forbidden</h1></html>".toByteArray() },
            ).prepare("https://media.example/episode.m3u8", emptyMap())
        }

        assertTrue(failure.message.orEmpty().contains("instead of an HLS playlist"))
    }

    @Test
    fun keepsProtectedHlsBehindHeaderRelayWhenInitialProbeFails() {
        val manifestUrl = "https://protected.example/episode.m3u8?auth_key=fresh"
        val segmentUrl = "https://protected.example/chunk.ts"
        val manifest = "#EXTM3U\n#EXTINF:3,\n$segmentUrl\n#EXT-X-ENDLIST\n".toByteArray()
        val transport = ByteArray(188 * 4) { index -> if (index % 188 == 0) 0x47 else 0x55 }
        val forwarded = mutableListOf<Map<String, String>>()
        val relayLoader = { location: String, headers: Map<String, String> ->
            forwarded += headers.toMap()
            when (location) {
                manifestUrl -> manifest
                segmentUrl -> transport
                else -> error("Unexpected resource $location")
            }
        }
        val headers = mapOf(
            "Origin" to "https://provider.example",
            "Referer" to "https://provider.example/",
            "User-Agent" to "Provider UA",
        )
        val prepared = HlsCompatibilityPreparer(
            fetch = { _, _ -> throw ConnectException("transient proxy failure") },
            relayFetch = relayLoader,
        ).prepare(manifestUrl, headers)
        try {
            assertNotEquals(manifestUrl, prepared.location)
            val relayedManifest = getText(prepared.location)
            val localSegment = relayedManifest.lineSequence().first { it.startsWith("http://127.0.0.1:") }
            val response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI(localSegment)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )

            assertArrayEquals(transport, response.body())
            assertEquals(2, forwarded.size)
            assertEquals(listOf(headers, headers), forwarded)
        } finally {
            prepared.resource?.close()
        }
    }

    @Test
    fun recoversImageWrappedHlsAfterTransientDnsFailureWithoutProtectedHeaders() {
        val manifestUrl = "https://media.example/episode.m3u8"
        val segmentUrl = "https://cdn.example/chunk.png"
        val manifest = "#EXTM3U\n#EXTINF:3,\n$segmentUrl\n#EXT-X-ENDLIST\n".toByteArray()
        val transport = ByteArray(188 * 4) { index -> if (index % 188 == 0) 0x47 else 0x31 }
        val wrapped = byteArrayOf(0x01, 0x02, 0x03) + transport
        var relayManifestAttempts = 0
        val relayLoader = { location: String, _: Map<String, String> ->
            when (location) {
                manifestUrl -> {
                    relayManifestAttempts++
                    if (relayManifestAttempts == 1) throw UnknownHostException("transient DNS failure")
                    manifest
                }
                segmentUrl -> wrapped
                else -> error("Unexpected resource $location")
            }
        }
        val prepared = HlsCompatibilityPreparer(
            fetch = { _, _ -> throw UnknownHostException("initial DNS failure") },
            relayFetch = relayLoader,
        ).prepare(manifestUrl, mapOf("User-Agent" to "test"))
        try {
            assertNotEquals(manifestUrl, prepared.location)
            val relayedManifest = getText(prepared.location)
            val localSegment = relayedManifest.lineSequence().first { it.startsWith("http://127.0.0.1:") }
            val response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI(localSegment)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )

            assertEquals(2, relayManifestAttempts)
            assertEquals("video/mp2t", response.headers().firstValue("Content-Type").orElse(""))
            assertArrayEquals(transport, response.body())
        } finally {
            prepared.resource?.close()
        }
    }

    @Test
    fun relaysWrappedMediaWhenManifestLoadsButSegmentProbeIsTransientlyUnavailable() {
        val manifestUrl = "https://media.example/episode.m3u8"
        val segmentUrl = "https://cdn.example/chunk.png"
        val manifest = "#EXTM3U\n#EXTINF:3,\n$segmentUrl\n#EXT-X-ENDLIST\n".toByteArray()
        val transport = ByteArray(188 * 4) { index -> if (index % 188 == 0) 0x47 else 0x39 }
        val wrapped = byteArrayOf(0x11, 0x22) + transport
        var segmentAttempts = 0
        val loader = { location: String, _: Map<String, String> ->
            when (location) {
                manifestUrl -> manifest
                segmentUrl -> {
                    segmentAttempts++
                    if (segmentAttempts == 1) throw javax.net.ssl.SSLHandshakeException("transient TLS failure")
                    wrapped
                }
                else -> error("Unexpected resource $location")
            }
        }
        val prepared = HlsCompatibilityPreparer(loader, loader).prepare(manifestUrl, emptyMap())
        try {
            val relayedManifest = getText(prepared.location)
            val localSegment = relayedManifest.lineSequence().first { it.startsWith("http://127.0.0.1:") }
            val response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI(localSegment)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )

            assertEquals(2, segmentAttempts)
            assertArrayEquals(transport, response.body())
        } finally {
            prepared.resource?.close()
        }
    }

    @Test
    fun rejectsStaleFirstSegmentBeforeLaunchingPlayer() {
        val manifestUrl = "https://media.example/episode.m3u8"
        val segmentUrl = "https://cdn.example/missing.png"
        val manifest = "#EXTM3U\n#EXTINF:3,\n$segmentUrl\n#EXT-X-ENDLIST\n".toByteArray()
        val failure = assertThrows(PlaybackPreparationException::class.java) {
            HlsCompatibilityPreparer(
                fetch = { location, _ ->
                    if (location == manifestUrl) manifest else throw DesktopHttpStatusException(404, "missing")
                },
            ).prepare(manifestUrl, emptyMap())
        }

        assertTrue(failure.message.orEmpty().contains("first HLS media segment"))
        assertTrue(failure.message.orEmpty().contains("HTTP 404"))
        assertTrue(failure.message.orEmpty().contains("stale or removed"))
    }

    @Test
    fun rejectsUnreachableManifestBeforeLaunchingPlayer() {
        var recoveryAttempts = 0
        val failure = assertThrows(PlaybackPreparationException::class.java) {
            HlsCompatibilityPreparer(
                fetch = { _, _ -> throw UnknownHostException("initial DNS failure") },
                relayFetch = { _, _ ->
                    recoveryAttempts++
                    throw UnknownHostException("persistent DNS failure")
                },
            ).prepare("https://media.example/episode.m3u8", emptyMap())
        }

        assertEquals(3, recoveryAttempts)
        assertTrue(failure.message.orEmpty().contains("Could not verify the HLS manifest"))
    }

    @Test
    fun stripsImageWrapperAndServesMpegTransportStream() {
        val manifestUrl = "https://media.example/show/episode.m3u8"
        val segmentUrl = "https://cdn.example/chunk.jpg"
        val transport = ByteArray(188 * 4) { index -> if (index % 188 == 0) 0x47 else 0x22 }
        val wrapped = byteArrayOf(0x01, 0x02, 0x03) + transport
        val resources = mapOf(
            manifestUrl to "#EXTM3U\n#EXTINF:3,\n$segmentUrl\n#EXT-X-ENDLIST\n".toByteArray(),
            segmentUrl to wrapped,
        )
        val loader = { location: String, _: Map<String, String> -> resources.getValue(location) }
        val prepared = HlsCompatibilityPreparer(loader, loader)
            .prepare(manifestUrl, mapOf("User-Agent" to "test"))
        try {
            assertNotEquals(manifestUrl, prepared.location)
            val client = HttpClient.newHttpClient()
            val manifest = client.send(
                HttpRequest.newBuilder(URI(prepared.location)).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            ).body()
            val localSegment = manifest.lineSequence().first { it.startsWith("http://127.0.0.1:") }
            val response = client.send(
                HttpRequest.newBuilder(URI(localSegment)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )

            assertEquals("video/mp2t", response.headers().firstValue("Content-Type").orElse(""))
            assertArrayEquals(transport, response.body())
        } finally {
            prepared.resource?.close()
        }
    }

    @Test
    fun relaysOnlyTheManifestForOrdinaryTransportStreamPlaylist() {
        val manifestUrl = "https://media.example/episode.m3u8"
        val segmentUrl = "https://media.example/chunk.ts"
        val transport = ByteArray(188 * 4) { index -> if (index % 188 == 0) 0x47 else 0x11 }
        val resources = mapOf(
            manifestUrl to "#EXTM3U\n#EXTINF:3,\n$segmentUrl\n".toByteArray(),
            segmentUrl to transport,
        )

        val loader = { location: String, _: Map<String, String> -> resources.getValue(location) }
        val prepared = HlsCompatibilityPreparer(loader, loader).prepare(manifestUrl, emptyMap())
        try {
            assertNotEquals(manifestUrl, prepared.location)
            val manifest = getText(prepared.location)

            assertEquals(segmentUrl, manifest.lineSequence().first { it.isNotBlank() && !it.startsWith('#') })
        } finally {
            prepared.resource?.close()
        }
    }

    @Test
    fun removesConfiguredAdSegmentsAndKeepsMediaOnTheUpstreamCdn() {
        val manifestUrl = "https://media.example/episode.m3u8"
        val adUrl = "https://media.example/ad.ts"
        val contentUrl = "https://media.example/content.ts"
        val transport = ByteArray(188 * 4) { index -> if (index % 188 == 0) 0x47 else 0x11 }
        val resources = mapOf(
            manifestUrl to """
                #EXTM3U
                #EXT-X-DISCONTINUITY
                #EXTINF:5.0,
                ad.ts
                #EXT-X-DISCONTINUITY
                #EXTINF:10.0,
                content.ts
                #EXT-X-ENDLIST
            """.trimIndent().toByteArray(),
            adUrl to transport,
            contentUrl to transport,
        )
        val loader = { location: String, _: Map<String, String> -> resources.getValue(location) }
        val preparer = HlsCompatibilityPreparer(loader, loader).apply {
            updateAdRules(listOf(HlsAdRule(
                "Demo",
                listOf("media.example"),
                listOf("#EXT-X-DISCONTINUITY\\n#EXTINF:5\\.0,\\nhttps://media\\.example/ad\\.ts\\n"),
            )))
        }
        val prepared = preparer.prepare(manifestUrl, mapOf("Referer" to "https://portal.example/"))
        try {
            val manifest = getText(prepared.location)
            val mediaLocation = manifest.lineSequence().first { it.isNotBlank() && !it.startsWith('#') }

            assertEquals(false, manifest.contains("ad.ts"))
            assertEquals(contentUrl, mediaLocation)
        } finally {
            prepared.resource?.close()
        }
    }

    @Test
    fun purifiesNestedMediaPlaylistReferencedByMasterManifest() {
        val masterUrl = "https://origin.example/master.m3u8"
        val childUrl = "https://cdn.example/video/index.m3u8"
        val adUrl = "https://cdn.example/video/ad.ts"
        val segmentUrl = "https://cdn.example/video/content.ts"
        val transport = ByteArray(188 * 4) { index -> if (index % 188 == 0) 0x47 else 0x44 }
        val resources = mapOf(
            masterUrl to "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\n$childUrl\n".toByteArray(),
            childUrl to """
                #EXTM3U
                #EXT-X-DISCONTINUITY
                #EXTINF:5.0,
                ad.ts
                #EXT-X-DISCONTINUITY
                #EXTINF:10.0,
                content.ts
                #EXT-X-ENDLIST
            """.trimIndent().toByteArray(),
            adUrl to transport,
            segmentUrl to transport,
        )
        val loader = { location: String, _: Map<String, String> -> resources.getValue(location) }
        val preparer = HlsCompatibilityPreparer(loader, loader).apply {
            updateAdRules(listOf(HlsAdRule(
                "Nested",
                listOf("cdn.example"),
                listOf("#EXT-X-DISCONTINUITY\\n#EXTINF:5\\.0,\\nhttps://cdn\\.example/video/ad\\.ts\\n"),
            )))
        }
        val prepared = preparer.prepare(masterUrl, emptyMap())
        try {
            val master = getText(prepared.location)
            val localChild = master.lineSequence().first { it.startsWith("http://127.0.0.1:") }
            val child = getText(localChild)

            assertEquals(false, child.contains("ad.ts"))
            assertEquals(segmentUrl, child.lineSequence().first { it.isNotBlank() && !it.startsWith('#') })
        } finally {
            prepared.resource?.close()
        }
    }

    @Test
    fun servesOriginalManifestWhenRemoteRuleIsInvalid() {
        val manifestUrl = "https://media.example/episode.m3u8"
        val segmentUrl = "https://media.example/content.ts"
        val transport = ByteArray(188 * 4) { index -> if (index % 188 == 0) 0x47 else 0x33 }
        val resources = mapOf(
            manifestUrl to "#EXTM3U\n#EXTINF:10.0,\ncontent.ts\n#EXT-X-ENDLIST\n".toByteArray(),
            segmentUrl to transport,
        )
        val loader = { location: String, _: Map<String, String> -> resources.getValue(location) }
        val preparer = HlsCompatibilityPreparer(loader, loader).apply {
            updateAdRules(listOf(HlsAdRule("Invalid", listOf("media.example"), listOf("#EXTINF:("))))
        }
        val prepared = preparer.prepare(manifestUrl, emptyMap())
        try {
            val manifest = getText(prepared.location)

            assertEquals(segmentUrl, manifest.lineSequence().first { it.isNotBlank() && !it.startsWith('#') })
        } finally {
            prepared.resource?.close()
        }
    }

    private fun getText(location: String): String = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI(location)).GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    ).body()
}
