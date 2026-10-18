package com.wuming.tv.core.media

import com.wuming.tv.core.media.catalog.MediaCatalogStore
import com.wuming.tv.core.media.catalog.MediaManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.HashMap
import java.util.Optional

class OpenDrivePipelineTest {

    @Test
    fun persistentInputUsesRawToUriToTargetRouteAndDeduplicates() {
        val catalog = FakeCatalog()
        val pipeline = pipeline(catalog)

        val first = pipeline.prepare("https://cdn.example/movie.mp4")
        val second = pipeline.prepare("https://cdn.example/movie.mp4")

        assertEquals(OpenDrivePipeline.Result.Status.READY, first.status)
        assertTrue(first.created)
        assertNotNull(first.uri)
        assertEquals("https://cdn.example/movie.mp4", first.target!!.url)
        assertEquals(first.uri, second.uri)
        assertFalse(second.created)
        assertEquals(1, catalog.manifests.size)
    }

    @Test
    fun signedInputResolvesWithoutPersistingSecretBearingLocator() {
        val catalog = FakeCatalog()

        val result = pipeline(catalog)
            .prepare("https://cdn.example/live.m3u8?token=secret")

        assertEquals(OpenDrivePipeline.Result.Status.TRANSIENT, result.status)
        assertNull(result.uri)
        assertEquals("https://cdn.example/live.m3u8?token=secret", result.target!!.url)
        assertTrue(catalog.manifests.isEmpty())
    }

    @Test
    fun canonicalUriCanBeResolvedAfterImport() {
        val catalog = FakeCatalog()
        val pipeline = pipeline(catalog)
        val uri = pipeline.prepare("https://cdn.example/movie.mp4").uri.toString()

        val resolved = pipeline.prepare(uri)

        assertEquals(OpenDrivePipeline.Result.Status.READY, resolved.status)
        assertEquals("https://cdn.example/movie.mp4", resolved.target!!.url)
    }

    @Test
    fun unknownCanonicalUriFailsClosed() {
        val result = pipeline(FakeCatalog())
            .prepare("opendrive://local/asset/missing")

        assertEquals(OpenDrivePipeline.Result.Status.NOT_FOUND, result.status)
        assertNull(result.target)
    }

    @Test
    fun malformedCanonicalUriIsInvalidRatherThanUnsupported() {
        val result = pipeline(FakeCatalog())
            .prepare("opendrive://local/v1/asset/movie")

        assertEquals(OpenDrivePipeline.Result.Status.INVALID, result.status)
    }

    @Test
    fun legacyIdsAreHandedBackToTheExistingEngine() {
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("legacy-handle".toByteArray(StandardCharsets.UTF_8))

        val result = pipeline(FakeCatalog())
            .prepare("opendrive-file:quark:$encoded")

        assertEquals(OpenDrivePipeline.Result.Status.LEGACY, result.status)
        assertNull(result.target)
    }

    @Test
    fun malformedLegacyIdFailsClosed() {
        val result = pipeline(FakeCatalog())
            .prepare("opendrive-file:quark:not-base64!")

        assertEquals(OpenDrivePipeline.Result.Status.INVALID, result.status)
    }

    private fun pipeline(catalog: MediaCatalogStore): OpenDrivePipeline {
        val decoder = InputDecoder { input ->
            if (input == null || !input.startsWith("https://")) DecodeResult.noMatch()
            else {
                val persistable = !input.contains("token=")
                val source = MediaSourceDescriptor(
                    "test.http", 1, mapOf("url" to input), MediaFingerprint.sha256(input),
                    0, persistable)
                DecodeResult.decoded(DecodedMedia(
                    OpenDriveUri.Kind.ASSET, "", listOf(source)))
            }
        }
        val resolver = object : SourceResolver {
            override fun adapterId() = "test.http"
            override fun resolve(source: MediaSourceDescriptor) =
                PlaybackTarget(source.locator["url"]!!, emptyMap(), 0L)
        }
        return OpenDrivePipeline(InputDecoderRegistry(listOf(decoder)), catalog,
            MediaResolverRegistry(listOf(resolver)))
    }

    private class FakeCatalog : MediaCatalogStore {
        val manifests = HashMap<OpenDriveUri, MediaManifest>()
        private val fingerprints = HashMap<String, OpenDriveUri>()

        override fun save(media: DecodedMedia): MediaCatalogStore.SaveResult {
            val fingerprint = media.sources[0].fingerprint
            val existing = fingerprints[fingerprint]
            if (existing != null) return MediaCatalogStore.SaveResult(existing, false)
            val uri = OpenDriveUri.local(media.kind)
            manifests[uri] = MediaManifest(uri, media.title, 1, media.sources)
            fingerprints[fingerprint] = uri
            return MediaCatalogStore.SaveResult(uri, true)
        }

        override fun find(uri: OpenDriveUri): Optional<MediaManifest> =
            Optional.ofNullable<MediaManifest>(manifests[uri])
    }
}
