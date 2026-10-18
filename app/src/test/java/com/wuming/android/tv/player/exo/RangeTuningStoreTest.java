package com.wuming.android.tv.player.exo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.wuming.tv.core.playback.RangeProbeOptimizer;
import com.wuming.tv.core.playback.RangeTuningProfile;

import org.junit.Test;

public class RangeTuningStoreTest {

    @Test
    public void environmentKeySeparatesMaterialTransferConditions() {
        String wifi = RangeTuningStore.environmentKey(
                "uc", "cdn.example", "wifi", false, false, false, 65536);

        assertNotEquals(wifi, RangeTuningStore.environmentKey(
                "uc", "cdn.example", "cellular", true, false, false, 65536));
        assertNotEquals(wifi, RangeTuningStore.environmentKey(
                "uc", "cdn.example", "wifi", false, false, true, 65536));
        assertNotEquals(wifi, RangeTuningStore.environmentKey(
                "uc", "other.example", "wifi", false, false, false, 65536));
        assertNotEquals(wifi, RangeTuningStore.environmentKey(
                "uc", "cdn.example", "wifi", false, false, false, 131072));
        assertNotEquals(wifi, RangeTuningStore.environmentKey(
                "uc", "cast", "cdn.example", "wifi", false, false, false,
                65536, 1024L));
        assertEquals(RangeTuningStore.fallbackEnvironmentKey(
                        "uc", "playback", "cdn.example", "wifi",
                        false, false, false),
                RangeTuningStore.fallbackEnvironmentKey(
                        "uc", "playback", "cdn.example", "wifi",
                        false, false, false));
        assertNotEquals(RangeTuningStore.fallbackEnvironmentKey(
                        "uc", "playback", "cdn.example", "wifi",
                        false, false, false),
                RangeTuningStore.fallbackEnvironmentKey(
                        "uc", "playback", "other.example", "wifi",
                        false, false, false));
    }

    @Test
    public void bandwidthUsesCoarsePowerOfTwoBuckets() {
        assertEquals(0, RangeTuningStore.bandwidthBucketKbps(0));
        assertEquals(32768, RangeTuningStore.bandwidthBucketKbps(40_000));
        assertEquals(65536, RangeTuningStore.bandwidthBucketKbps(60_000));
        assertEquals(131072, RangeTuningStore.bandwidthBucketKbps(100_000));
        assertEquals(1024L, RangeTuningStore.contentSizeBucket(900L));
        assertEquals(2048L, RangeTuningStore.contentSizeBucket(1800L));
    }

    @Test
    public void successfulSamplesIncreaseConfidenceAndSmoothThroughput() {
        long now = 1_000_000L;
        RangeTuningProfile first = RangeTuningStore.merge(
                null, 4, 2L * 1024L * 1024L, 8_000_000L, now);
        RangeTuningProfile second = RangeTuningStore.merge(
                first, 6, 4L * 1024L * 1024L, 12_000_000L, now + 1L);

        assertEquals(1, first.confidence());
        assertEquals(2, second.confidence());
        assertEquals(6, second.ranges());
        assertEquals(4L * 1024L * 1024L, second.rangeBytes());
        assertEquals(9_000_000L, second.observedBps());
        assertEquals(second, RangeTuningProfile.decode(second.encode()));
    }

    @Test
    public void legacyProfileDecodesWithSafeProbeDefaults() {
        RangeTuningProfile profile = RangeTuningProfile.decode(
                "4,2097152,8000000,2,1000");

        assertEquals(4, profile.ranges());
        assertEquals(RangeProbeOptimizer.AXIS_COUNT, profile.nextAxis());
        assertEquals(1, profile.countDirection());
        assertEquals(1, profile.sizeDirection());
        assertEquals(0, profile.probeCooldown());
        assertEquals(0L, profile.probeNotBefore());
        assertEquals(0L, profile.revision());
    }

    @Test
    public void revisionRejectsStaleDataSourceWriter() {
        RangeTuningProfile current = new RangeTuningProfile(
                4, 1024L, 1_000_000L, 2, 100L,
                RangeProbeOptimizer.AXIS_SIZE, 1, 1,
                RangeProbeOptimizer.REJECTED_PROBE_COOLDOWN, 500L, 7L);

        assertEquals(current, RangeTuningProfile.decode(current.encode()));
        assertTrue(RangeTuningStore.revisionMatches(current, 7L));
        assertFalse(RangeTuningStore.revisionMatches(current, 6L));
        assertFalse(RangeTuningStore.revisionMatches(current, 0L));
    }

    @Test
    public void legacyAdaptiveSuccessPreservesMonotonicRevision() {
        RangeTuningProfile previous = new RangeTuningProfile(
                4, 1024L, 1_000_000L, 2, 100L,
                RangeProbeOptimizer.AXIS_COUNT, 1, 1, 0, 0L, 9L);

        RangeTuningProfile merged = RangeTuningStore.mergeSuccess(
                previous, 5, 2048L, 2_000_000L, 200L);

        assertEquals(10L, merged.revision());
        assertEquals(5, merged.ranges());
        assertEquals(2048L, merged.rangeBytes());
        assertEquals(3, merged.confidence());
    }

    @Test
    public void pragmaticFallbackKeepsSettingButRequiresFreshProbe() {
        RangeTuningProfile exact = new RangeTuningProfile(
                6, 4L * 1024L * 1024L, 12_000_000L, 7, 100L,
                RangeProbeOptimizer.AXIS_SIZE, -1, 1,
                RangeProbeOptimizer.REJECTED_PROBE_COOLDOWN, 10_000L, 4L);

        RangeTuningProfile fallback = RangeTuningStore.asFallback(exact);

        assertEquals(6, fallback.ranges());
        assertEquals(4L * 1024L * 1024L, fallback.rangeBytes());
        assertEquals(12_000_000L, fallback.observedBps());
        assertEquals(2, fallback.confidence());
        assertEquals(0, fallback.probeCooldown());
        assertEquals(0L, fallback.probeNotBefore());
    }

    @Test
    public void staleAndMalformedProfilesAreRejected() {
        RangeTuningProfile profile = new RangeTuningProfile(
                4, 1024L, 1_000_000L, 2, 100L);

        assertTrue(RangeTuningStore.fresh(profile, 101L));
        assertFalse(RangeTuningStore.fresh(profile,
                100L + RangeTuningStore.PROFILE_TTL_MILLIS + 1L));
        assertNull(RangeTuningProfile.decode("not,a,profile"));
        assertNull(RangeTuningProfile.decode("0,1024,1,1,100"));
    }
}
