package com.wuming.tv.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.LinkedHashMap

class OpenDrivePlaybackHintsTest {

    @Test
    fun hlsCompositionRemovesStaleReservedHintsCaseInsensitively() {
        val input = LinkedHashMap<String, String>()
        input["Referer"] = "https://drive.example/"
        input["x-opendrive-hls"] = "false"
        input["x-OPENdrive-explicit-cookie"] = "true"

        val headers = OpenDrivePlaybackHints.hls(input, false)

        assertEquals("https://drive.example/", headers["Referer"])
        assertTrue(OpenDrivePlaybackHints.enabled(headers, OpenDrivePlaybackHints.HLS))
        assertFalse(OpenDrivePlaybackHints.enabled(headers, OpenDrivePlaybackHints.EXPLICIT_COOKIE))
        assertEquals(2, headers.size)
    }

    @Test
    fun invalidParallelRequestCannotRetainStaleRangeHints() {
        val input = LinkedHashMap<String, String>()
        input["x-opendrive-content-length"] = "999"
        input["X-OPENDRIVE-PARALLEL-RANGE"] = "unknown-profile"
        input["Authorization"] = "Bearer opaque"

        val headers = OpenDrivePlaybackHints.parallelRanges(input, "unknown-profile", -1L)

        assertEquals(mapOf("Authorization" to "Bearer opaque"), headers)
    }

    @Test
    fun manifestRetryCanRemoveHlsWithoutDroppingAuthentication() {
        var headers = OpenDrivePlaybackHints.hls(mapOf("Cookie" to "signed=value"), true)
        headers = OpenDrivePlaybackHints.without(headers, OpenDrivePlaybackHints.HLS)

        assertFalse(OpenDrivePlaybackHints.enabled(headers, OpenDrivePlaybackHints.HLS))
        assertTrue(OpenDrivePlaybackHints.enabled(headers, OpenDrivePlaybackHints.EXPLICIT_COOKIE))
        assertEquals("signed=value", headers["Cookie"])
    }

    @Test
    fun provenProfileCarriesInternalLengthAndCapability() {
        val headers = OpenDrivePlaybackHints.parallelRanges(
            mapOf("Referer" to "https://www.alipan.com/"),
            OpenDrivePlaybackHints.PROFILE_ALIPAN, 42L)

        assertEquals("42", headers[OpenDrivePlaybackHints.CONTENT_LENGTH])
        assertEquals(OpenDrivePlaybackHints.PROFILE_ALIPAN, headers[OpenDrivePlaybackHints.PARALLEL_RANGE_PROFILE])
        assertEquals("https://www.alipan.com/", headers["Referer"])
    }

    @Test
    fun parallelLengthPreservesUnsigned32BitBoundaryAndBeyond() {
        val fiveGiB = 5L * 1024L * 1024L * 1024L
        val headers = OpenDrivePlaybackHints.parallelRanges(emptyMap(), OpenDrivePlaybackHints.PROFILE_UC_PDS, fiveGiB)
        assertEquals("5368709120", headers[OpenDrivePlaybackHints.CONTENT_LENGTH])
    }

    @Test
    fun unknownProfileNeverEnablesParallelTransport() {
        val headers = OpenDrivePlaybackHints.parallelRanges(
            mapOf("Referer" to "https://example.com/"), "guessed-provider", 42L)

        assertFalse(headers.containsKey(OpenDrivePlaybackHints.CONTENT_LENGTH))
        assertFalse(headers.containsKey(OpenDrivePlaybackHints.PARALLEL_RANGE_PROFILE))
    }

    @Test
    fun quarkOriginalUsesTheReviewedDownloadMtProfile() {
        val headers = OpenDrivePlaybackHints.parallelRanges(
            emptyMap(), OpenDrivePlaybackHints.PROFILE_QUARK_ORIGINAL, 548_726_389L)

        assertEquals("548726389", headers[OpenDrivePlaybackHints.CONTENT_LENGTH])
        assertEquals(OpenDrivePlaybackHints.PROFILE_QUARK_ORIGINAL, headers[OpenDrivePlaybackHints.PARALLEL_RANGE_PROFILE])
    }

    @Test
    fun baiduAndGenericRangeProfilesAreExplicitCapabilities() {
        assertTrue(OpenDrivePlaybackHints.validProfile(OpenDrivePlaybackHints.PROFILE_BAIDU_ORIGINAL))
        assertTrue(OpenDrivePlaybackHints.validProfile(OpenDrivePlaybackHints.PROFILE_GENERIC_HTTP))
    }

    @Test
    fun refreshCompositionReplacesStaleMetadataWithoutChangingHttpHeaders() {
        val input = LinkedHashMap<String, String>()
        input["Referer"] = "https://app.example/"
        input["x-opendrive-refresh-uri"] = "opendrive://local/asset/stale"
        input["X-OpenDrive-Expires-At"] = "1"

        val headers = OpenDrivePlaybackHints.refreshable(input, "opendrive://local/asset/current", 5_368_709_120L)

        assertEquals("opendrive://local/asset/current", headers[OpenDrivePlaybackHints.REFRESH_URI])
        assertEquals("5368709120", headers[OpenDrivePlaybackHints.EXPIRES_AT])
        assertEquals("https://app.example/", headers["Referer"])
        assertEquals(3, headers.size)
    }
}
