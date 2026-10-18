package com.wuming.tv.core.media

import com.wuming.tv.core.media.catalog.MediaCatalogStore
import com.wuming.tv.core.media.catalog.MediaManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Optional

class OpenDriveProviderIdentityCodecTest {

    @Test
    fun catalogStoresOnlyOpaqueHandleAndVaultReference() {
        val catalog = CapturingCatalog()
        val uri = OpenDriveProviderIdentityCodec(catalog)
            .encode("quark", "01234567-89ab-cdef-0123-456789abcdef", "Movie")

        val source = catalog.media!!.sources[0]
        assertEquals("opendrive://local/asset/provider", uri)
        assertEquals("quark", source.locator["provider"])
        assertEquals("01234567-89ab-cdef-0123-456789abcdef", source.locator["handle"])
        assertEquals("vault:opendrive/quark/01234567-89ab-cdef-0123-456789abcdef",
            source.credentialRef)
        assertFalse(source.locator.toString().contains("token"))
    }

    private class CapturingCatalog : MediaCatalogStore {
        var media: DecodedMedia? = null

        override fun save(media: DecodedMedia): MediaCatalogStore.SaveResult {
            this.media = media
            return MediaCatalogStore.SaveResult(OpenDriveUri("local", OpenDriveUri.Kind.ASSET, "provider"), true)
        }

        override fun find(uri: OpenDriveUri): Optional<MediaManifest> = Optional.empty()
    }
}
