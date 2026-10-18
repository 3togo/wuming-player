package com.wuming.tv.core.media

import com.wuming.tv.core.media.catalog.MediaCatalogStore
import com.wuming.tv.core.media.catalog.MediaManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.HashMap
import java.util.Optional

class OpenDriveIdentityCodecTest {

    @Test
    fun encodesEquivalentPersistablePlaybackIdempotently() {
        val catalog = FakeCatalog()
        val codec = codec(catalog)

        val first = codec.encode("https://cdn.example/movie.mp4", emptyMap())
        val second = codec.encode("https://cdn.example/movie.mp4", emptyMap())

        assertTrue(OpenDriveUri.isValid(first))
        assertEquals(first, second)
        assertEquals(1, catalog.manifests.size)
    }

    @Test
    fun leavesCredentialedOrHeaderDependentPlaybackTransient() {
        val catalog = FakeCatalog()
        val codec = codec(catalog)

        assertEquals("", codec.encode(
            "https://cdn.example/movie.mp4", mapOf("Referer" to "https://example.test/")))
        assertEquals("", codec.encode(
            "https://cdn.example/movie.mp4?token=secret", emptyMap()))
        assertTrue(catalog.manifests.isEmpty())
    }

    private fun codec(catalog: MediaCatalogStore): OpenDriveIdentityCodec {
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
        val decoders = InputDecoderRegistry(listOf(decoder))
        val resolvers = MediaResolverRegistry(listOf(resolver))
        val pipeline = OpenDrivePipeline(decoders, catalog, resolvers)
        return OpenDriveIdentityCodec(
            pipeline, OpenDriveShadowProbe(decoders, resolvers))
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
