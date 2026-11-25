package com.wuming.tv.core.media

import com.wuming.tv.core.cloud.OpenDriveFailure
import com.wuming.tv.core.media.catalog.MediaManifest
import org.junit.Assert.*
import org.junit.Test

class MediaResolverRegistryCloudFailureTest {
    @Test fun typedCloudFailureDoesNotBecomeAnUnsupportedResourceOrChooseAnotherSource() {
        for (reason in OpenDriveFailure.Reason.entries) {
            val error = OpenDriveFailure("quark", reason)
            var alternateCalls = 0
            val resolver = object : SourceResolver {
                override fun adapterId() = "fixture"
                override fun resolve(source: MediaSourceDescriptor): PlaybackTarget {
                    if (source.locator["id"] == "first") throw IllegalStateException("wrapped", error)
                    alternateCalls++
                    return PlaybackTarget("https://example.invalid/alternate.mp4", emptyMap(), 0)
                }
            }
            val manifest = MediaManifest(OpenDriveUri.local(OpenDriveUri.Kind.ASSET), "fixture", 1,
                listOf(source("first"), source("second")))
            val caught = assertThrows(OpenDriveFailure::class.java) { MediaResolverRegistry(listOf(resolver)).resolve(manifest) }
            assertSame(error, caught)
            assertEquals(0, alternateCalls)
        }
    }

    @Test fun ordinaryBrokenSourceCanStillUseTheNextManifestSource() {
        val resolver = object : SourceResolver {
            override fun adapterId() = "fixture"
            override fun resolve(source: MediaSourceDescriptor): PlaybackTarget {
                if (source.locator["id"] == "first") throw IllegalArgumentException("old source")
                return PlaybackTarget("https://example.invalid/alternate.mp4", emptyMap(), 0)
            }
        }
        val manifest = MediaManifest(OpenDriveUri.local(OpenDriveUri.Kind.ASSET), "fixture", 1,
            listOf(source("first"), source("second")))
        assertEquals("https://example.invalid/alternate.mp4",
            MediaResolverRegistry(listOf(resolver)).resolve(manifest).target!!.url)
    }

    private fun source(id: String) =
        MediaSourceDescriptor("fixture", 1, mapOf("id" to id), MediaFingerprint.sha256(id), 1, true)
}
