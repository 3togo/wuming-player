package com.wuming.tv.core.playback

import org.junit.Assert.*
import org.junit.Test

class CastRelayTransferTest {
    @Test fun headReportsMediaSizeWithoutDownloadingItsBody() {
        assertEquals(9000000000L, CastRelayTransfer.responseLength(true, 0, "9000000000"))
        assertEquals(123L, CastRelayTransfer.responseLength(false, 123, "999"))
        assertEquals(0L, CastRelayTransfer.responseLength(true, 0, "bad"))
    }
    @Test fun preservesRendererOffsetAndLength() {
        assertEquals(CastRelayTransfer.Slice(123, 100, 900),
            CastRelayTransfer.slice(206, 100, "bytes 123-222/900", null, null))
        assertEquals(CastRelayTransfer.Slice(0, 900, 900),
            CastRelayTransfer.slice(200, 900, null, "bytes", null))
        for (range in listOf("bytes 123-223/900", "bytes 123-222/*", "bytes 123-222/222",
                "bytes 0-9223372036854775807/9223372036854775807", "bytes 0-99/999999999999999999999")) {
            assertNull(CastRelayTransfer.slice(206, 100, range, "bytes", null))
        }
    }
    @Test fun leavesUnknownEncodedErrorsAndNonRangeBodiesAlone() {
        assertNull(CastRelayTransfer.slice(200, -1, null, "bytes", null))
        assertNull(CastRelayTransfer.slice(200, 100, null, null, null))
        assertNull(CastRelayTransfer.slice(403, 100, null, "bytes", null))
        assertNull(CastRelayTransfer.slice(200, 100, null, "bytes", "gzip"))
    }
    @Test fun retainsProviderProfileAndPrivateCookieButNotControlHeaders() {
        for (profile in listOf(OpenDrivePlaybackHints.PROFILE_QUARK_ORIGINAL,
                OpenDrivePlaybackHints.PROFILE_UC_PDS, OpenDrivePlaybackHints.PROFILE_ALIPAN,
                OpenDrivePlaybackHints.PROFILE_BAIDU_ORIGINAL)) {
            val original = PlaybackTransportPlan.parse("https://example.test/file.mp4", null, mapOf(
                OpenDrivePlaybackHints.CONTENT_LENGTH to "99000000",
                OpenDrivePlaybackHints.PARALLEL_RANGE_PROFILE to profile, "X-OpenDrive-Future" to "private", "Cookie" to "fixture"))
            val plan = CastRelayTransfer.plan(original, CastRelayTransfer.Slice(700, 5000000, 99000000))!!
            assertEquals(profile, plan.parallelProfile)
            assertTrue(plan.parallelRanges())
            assertTrue(plan.explicitCookie)
            assertEquals(mapOf("Cookie" to "fixture"), plan.networkHeaders)
            assertEquals(PlaybackTransportPlan.CachePolicy.NONE, plan.cachePolicy)
        }
    }
    @Test fun genericMediaUsesPlaybackRangesButManifestsAndSmallProbesDoNot() {
        val large = CastRelayTransfer.Slice(0, 30000000, 30000000)
        val plain = PlaybackTransportPlan.parse("https://example.test/file.mp4", null, emptyMap())
        assertEquals(OpenDrivePlaybackHints.PROFILE_GENERIC_HTTP, CastRelayTransfer.plan(plain, large)!!.parallelProfile)
        assertNull(CastRelayTransfer.plan(plain, CastRelayTransfer.Slice(0, 1000, 30000000)))
        for (suffix in listOf("m3u8", "mpd")) {
            assertNull(CastRelayTransfer.plan(PlaybackTransportPlan.parse("https://example.test/a.$suffix", null, emptyMap()), large))
        }
    }
    @Test fun excludesLocalProxiesFromRecursiveParallelDownloads() {
        for (url in listOf("http://127.0.0.1/proxy", "http://127.2.3.4/a", "http://[::1]/a",
                "http://localhost/a", "http://a.localhost/a", "file:///x", "bad url")) {
            assertFalse(url, CastRelayTransfer.remoteHttp(url))
        }
        assertTrue(CastRelayTransfer.remoteHttp("https://cdn.example.test/a"))
    }
}
