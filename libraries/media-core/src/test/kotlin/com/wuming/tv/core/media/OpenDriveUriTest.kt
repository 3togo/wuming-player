package com.wuming.tv.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenDriveUriTest {

    @Test
    fun canonicalUriRoundTripsWithoutProviderOrSchemaVersion() {
        val uri = OpenDriveUri("LOCAL", OpenDriveUri.Kind.ASSET, "movie-01")
        assertEquals("opendrive://local/asset/movie-01", uri.toString())
        assertEquals(uri, OpenDriveUri.parse(uri.toString()))
    }

    @Test
    fun catalogAuthorityAndResourceKindAreIndependentOfProvider() {
        val quark = OpenDriveUri("home", OpenDriveUri.Kind.ASSET, "stable-id")
        val webDav = OpenDriveUri("home", OpenDriveUri.Kind.ASSET, "stable-id")
        assertEquals(quark, webDav)
        assertFalse(quark.toString().contains("quark"))
        assertFalse(quark.toString().contains("webdav"))
        assertFalse(quark.toString().contains("v1"))
    }

    @Test
    fun rejectsNonCanonicalOrCapabilityBearingUris() {
        assertFalse(OpenDriveUri.isValid("opendrive://local/v1/asset/id"))
        assertFalse(OpenDriveUri.isValid("opendrive://local/asset/id?token=secret"))
        assertFalse(OpenDriveUri.isValid("opendrive://local/asset/id#fragment"))
        assertFalse(OpenDriveUri.isValid("OPENDRIVE://local/asset/id"))
        assertFalse(OpenDriveUri.isValid("opendrive://quark/asset/id/extra"))
        assertThrows(IllegalArgumentException::class.java) {
            OpenDriveUri("bad..authority", OpenDriveUri.Kind.ASSET, "id")
        }
    }

    @Test
    fun generatedLocalUrisAreCanonicalAndUnique() {
        val first = OpenDriveUri.local(OpenDriveUri.Kind.COLLECTION)
        val second = OpenDriveUri.local(OpenDriveUri.Kind.COLLECTION)
        assertTrue(OpenDriveUri.isValid(first.toString()))
        assertFalse(first == second)
    }

    @Test
    fun everyResourceKindHasAStableCanonicalPath() {
        for (kind in OpenDriveUri.Kind.entries) {
            val uri = OpenDriveUri("catalog.example", kind, "resource_01~copy")
            val expected = "opendrive://catalog.example/${kind.value()}/resource_01~copy"
            assertEquals(expected, uri.toString())
            assertEquals(uri, OpenDriveUri.parse(expected))
            assertTrue(OpenDriveUri.isValid(expected))
        }
    }

    @Test
    fun acceptsDocumentedAuthorityAndResourceBoundaries() {
        val maximumAuthority = "a" + "b".repeat(126) + "z"
        val maximumResourceId = "r" + "x".repeat(127)
        val values = listOf(
            OpenDriveUri("a", OpenDriveUri.Kind.ASSET, "r"),
            OpenDriveUri("node-1.catalog.example", OpenDriveUri.Kind.LIVE, "A0._~-z9"),
            OpenDriveUri(maximumAuthority, OpenDriveUri.Kind.COLLECTION, maximumResourceId),
        )
        for (uri in values) {
            assertEquals(uri, OpenDriveUri.parse(uri.toString()))
            assertTrue(OpenDriveUri.isValid(uri.toString()))
        }
    }

    @Test
    fun rejectsNonCanonicalUriCorpusWithoutThrowingFromIsValid() {
        val invalid = listOf(
            null,
            "",
            "   ",
            "https://local/asset/id",
            "opendrive:local/asset/id",
            "opendrive:///asset/id",
            "opendrive://local",
            "opendrive://local/asset",
            "opendrive://local//id",
            "opendrive://local/unknown/id",
            "opendrive://local/ASSET/id",
            "opendrive://LOCAL/asset/id",
            "OPENDRIVE://local/asset/id",
            "opendrive://user@local/asset/id",
            "opendrive://local:443/asset/id",
            "opendrive://local/asset/id/extra",
            "opendrive://local/asset/id/",
            "opendrive://local/asset/%69d",
            "opendrive://local/asset/id%2Fextra",
            "opendrive://local/asset/id?token=capability",
            "opendrive://local/asset/id#fragment",
            "opendrive://bad..authority/asset/id",
            "opendrive://-local/asset/id",
            "opendrive://local-/asset/id",
            "opendrive://local/asset/-id",
            "opendrive://local/asset/id+token",
            "opendrive://local/asset/资源",
            "opendrive://local\\evil/asset/id",
            "opendrive://local/asset/id ",
        )
        for (value in invalid) {
            assertFalse("Accepted invalid OpenDrive URI: $value", OpenDriveUri.isValid(value))
        }
    }

    @Test
    fun constructorsRejectValuesBeyondContractLimits() {
        assertThrows(NullPointerException::class.java) {
            OpenDriveUri(null, OpenDriveUri.Kind.ASSET, "id")
        }
        assertThrows(NullPointerException::class.java) {
            OpenDriveUri("local", null, "id")
        }
        assertThrows(NullPointerException::class.java) {
            OpenDriveUri("local", OpenDriveUri.Kind.ASSET, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OpenDriveUri("a" + "b".repeat(128), OpenDriveUri.Kind.ASSET, "id")
        }
        assertThrows(IllegalArgumentException::class.java) {
            OpenDriveUri("local", OpenDriveUri.Kind.ASSET, "r" + "x".repeat(128))
        }
        assertFalse(OpenDriveUri.isValid("opendrive://local/asset/" + "x".repeat(600)))
    }

    @Test
    fun schemeDetectionRoutesMalformedOpenDriveUrisToFailClosedHandling() {
        assertTrue(OpenDriveUri.hasScheme("opendrive://local/asset/id"))
        assertTrue(OpenDriveUri.hasScheme("OPENDRIVE://local/asset/id"))
        assertTrue(OpenDriveUri.hasScheme("opendrive:not-canonical"))
        assertFalse(OpenDriveUri.hasScheme("x-opendrive://local/asset/id"))
        assertFalse(OpenDriveUri.hasScheme("https://local/asset/id"))
        assertFalse(OpenDriveUri.hasScheme(null))
    }
}
