package com.wuming.tv.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.LinkedHashMap

class PlaybackTransportPlanTest {

    @Test
    fun everyCloudFallbackReasonSurvivesMetadataButNeverReachesMediaServer() {
        for (reason in OpenDrivePlaybackHints.FallbackReason.entries) {
            val headers = OpenDrivePlaybackHints.fallback(mapOf("Referer" to "https://fixture.example"), reason)
            assertEquals(reason, OpenDrivePlaybackHints.fallbackReason(headers))
            val mixed = LinkedHashMap(headers)
            mixed["x-opendrive-fallback-reason"] = "untrusted-value"
            mixed["x-opendrive-copy-lease"] = "opaque-lease"
            mixed[OpenDrivePlaybackHints.COPY_LEASE] = "another-lease"
            val plan = PlaybackTransportPlan.parse("https://cdn.example/video.mp4", null, mixed)
            assertEquals(mapOf("Referer" to "https://fixture.example"), plan.networkHeaders)
        }
        assertEquals(OpenDrivePlaybackHints.FallbackReason.NONE,
            OpenDrivePlaybackHints.fallbackReason(mapOf(OpenDrivePlaybackHints.FALLBACK_REASON to "secret")))
    }

    @Test
    fun hlsNeverActivatesProgressiveParallelRanges() {
        val headers = LinkedHashMap<String, String>()
        headers[OpenDrivePlaybackHints.HLS] = "true"
        headers[OpenDrivePlaybackHints.CONTENT_LENGTH] = "5368709120"
        headers[OpenDrivePlaybackHints.PARALLEL_RANGE_PROFILE] = OpenDrivePlaybackHints.PROFILE_QUARK_ORIGINAL

        val plan = PlaybackTransportPlan.parse("https://cdn.example/extensionless", null, headers)

        assertEquals(PlaybackTransportPlan.Kind.HLS, plan.kind)
        assertFalse(plan.parallelRanges())
        assertEquals(0L, plan.contentLength)
        assertEquals("", plan.parallelProfile)
        assertTrue(plan.networkHeaders.isEmpty())
    }

    @Test
    fun progressivePlanKeepsLargeLengthAndConsumesAllInternalHints() {
        val headers = LinkedHashMap<String, String>()
        headers[OpenDrivePlaybackHints.CONTENT_LENGTH] = "5368709120"
        headers[OpenDrivePlaybackHints.PARALLEL_RANGE_PROFILE] = OpenDrivePlaybackHints.PROFILE_QUARK_ORIGINAL
        headers[OpenDrivePlaybackHints.EXPLICIT_COOKIE] = "true"
        headers["Cookie"] = "__puus=rotated"

        val plan = PlaybackTransportPlan.parse("https://cdn.example/video.mp4", null, headers)

        assertEquals(5L * 1024L * 1024L * 1024L, plan.contentLength)
        assertEquals(OpenDrivePlaybackHints.PROFILE_QUARK_ORIGINAL, plan.parallelProfile)
        assertTrue(plan.explicitCookie)
        assertEquals(mapOf("Cookie" to "__puus=rotated"), plan.networkHeaders)
    }

    @Test
    fun quarkSmartFallbackNoticeNeverReachesMediaServer() {
        val plan = PlaybackTransportPlan.parse("https://cdn.example/video.mp4", null,
            mapOf(OpenDrivePlaybackHints.QUARK_SMART_ORIGINAL_FALLBACK to "true",
                "Referer" to "https://pan.quark.cn/"))

        assertEquals(mapOf("Referer" to "https://pan.quark.cn/"), plan.networkHeaders)
    }

    @Test
    fun duplicateCaseInsensitiveFlagsAreConsumedBySinglePlan() {
        val headers = LinkedHashMap<String, String>()
        headers["x-opendrive-hls"] = "not-a-boolean"
        headers["X-OpenDrive-Hls"] = "true"
        headers["Referer"] = "https://pan.quark.cn/"

        val plan = PlaybackTransportPlan.parse("https://cdn.example/extensionless", null, headers)

        assertEquals(PlaybackTransportPlan.Kind.HLS, plan.kind)
        assertEquals(mapOf("Referer" to "https://pan.quark.cn/"), plan.networkHeaders)
    }

    @Test
    fun authenticatedAndLoopbackMediaNeverWritesSharedCache() {
        val authenticated = PlaybackTransportPlan.parse("https://cdn.example/video.mp4", null,
            mapOf("Authorization" to "Bearer opaque"))
        val loopback = PlaybackTransportPlan.parse("http://127.0.0.1:9978/proxy/video", null, emptyMap())

        assertEquals(PlaybackTransportPlan.CachePolicy.NONE, authenticated.cachePolicy)
        assertEquals(PlaybackTransportPlan.CachePolicy.NONE, loopback.cachePolicy)
    }

    @Test
    fun loopbackM3u8ProxyIsClassifiedBeforeProgressiveRetries() {
        val plan = PlaybackTransportPlan.parse(
            "http://127.0.0.1:9978/proxy?do=m3u8&url=https%3A%2F%2Fcdn.example%2Fvideo", null, emptyMap())

        assertEquals(PlaybackTransportPlan.Kind.HLS, plan.kind)
        assertEquals(PlaybackTransportPlan.CachePolicy.NONE, plan.cachePolicy)
    }

    @Test
    fun publicProgressiveMediaUsesWriteThroughCache() {
        val plan = PlaybackTransportPlan.parse("https://cdn.example/video.mp4?edition=restored", null,
            mapOf("Referer" to "https://app.example/"))

        assertEquals(PlaybackTransportPlan.Kind.PROGRESSIVE, plan.kind)
        assertEquals(PlaybackTransportPlan.CachePolicy.WRITE_THROUGH, plan.cachePolicy)
        assertFalse(plan.parallelRanges())
    }

    @Test
    fun unrecognizedRangeCapabilityCannotActivateParallelRecovery() {
        val plan = PlaybackTransportPlan.parse("https://cdn.example/video.mp4", null,
            mapOf(OpenDrivePlaybackHints.CONTENT_LENGTH to "5368709120",
                OpenDrivePlaybackHints.PARALLEL_RANGE_PROFILE to "ordinary-http"))

        assertFalse(plan.parallelRanges())
        assertEquals(0L, plan.contentLength)
        assertEquals("", plan.parallelProfile)
        assertTrue(plan.networkHeaders.isEmpty())
    }

    @Test
    fun untrackedSignedUrlIsNotPersistedInCacheIndex() {
        val plan = PlaybackTransportPlan.parse("https://cdn.example/video.mp4?token=secret&expires=9", null, emptyMap())
        assertEquals(PlaybackTransportPlan.CachePolicy.NONE, plan.cachePolicy)
    }

    @Test
    fun refreshMetadataIsConsumedAndCreatesOpaqueStableCacheKey() {
        val plan = PlaybackTransportPlan.parse("https://cdn.example/signed.mp4?token=secret", null,
            OpenDrivePlaybackHints.refreshable(mapOf("Referer" to "https://app.example/"),
                "opendrive://local/asset/abcdef", 9_999_999L))

        assertTrue(plan.refreshable())
        assertEquals(9_999_999L, plan.expiresAtEpochMs)
        assertTrue(plan.cacheKey.startsWith("media-"))
        assertFalse(plan.cacheKey.contains("abcdef"))
        assertEquals(mapOf("Referer" to "https://app.example/"), plan.networkHeaders)
    }

    @Test
    fun explicitMimeTakesPriorityOverSignedUrlExtension() {
        val plan = PlaybackTransportPlan.parse("https://cdn.example/download.mp4", "application/dash+xml", emptyMap())

        assertEquals(PlaybackTransportPlan.Kind.DASH, plan.kind)
        assertTrue(plan.adaptive())
        assertFalse(plan.refreshable())
    }
}
