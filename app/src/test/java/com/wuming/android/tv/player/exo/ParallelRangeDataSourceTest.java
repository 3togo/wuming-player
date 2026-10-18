package com.wuming.android.tv.player.exo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.wuming.tv.core.playback.OpenDrivePlaybackHints;

import org.junit.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.ConcurrentLinkedQueue;

public class ParallelRangeDataSourceTest {

    @Test
    public void validatedRangeProfilesHaveBoundedRecoveryPolicies() {
        ParallelRangeDataSource.RangePolicy quark = ParallelRangeDataSource.RangePolicy
                .forProfile(OpenDrivePlaybackHints.PROFILE_QUARK_ORIGINAL);
        ParallelRangeDataSource.RangePolicy uc = ParallelRangeDataSource.RangePolicy
                .forProfile(OpenDrivePlaybackHints.PROFILE_UC_PDS);
        ParallelRangeDataSource.RangePolicy aliPan = ParallelRangeDataSource.RangePolicy
                .forProfile(OpenDrivePlaybackHints.PROFILE_ALIPAN);
        ParallelRangeDataSource.RangePolicy baidu = ParallelRangeDataSource.RangePolicy
                .forProfile(OpenDrivePlaybackHints.PROFILE_BAIDU_ORIGINAL);
        ParallelRangeDataSource.RangePolicy generic = ParallelRangeDataSource.RangePolicy
                .forProfile(OpenDrivePlaybackHints.PROFILE_GENERIC_HTTP);

        assertNotNull(quark);
        assertEquals(ParallelRangeDataSource.QUARK_WINDOW_BYTES,
                quark.initialWindowBytes());
        assertEquals(ParallelRangeDataSource.QUARK_PARALLELISM, quark.parallelism());
        assertEquals(ParallelRangeDataSource.QUARK_PARALLELISM,
                quark.maximumParallelism());
        assertEquals(8, quark.minimumParallelism());
        assertEquals(32, quark.parallelism());
        assertEquals(128L * 1024L, ParallelRangeDataSource.QUARK_PART_BYTES);
        assertEquals(true, quark.prefetchRanges());
        assertEquals(false, quark.adaptive());
        assertEquals(true, quark.rangeTuning());
        assertEquals(true, quark.refreshSlowRanges());
        assertEquals(true, quark.strictContentRange());
        assertEquals(true, quark.sequentialFallback());

        assertNotNull(uc);
        assertEquals(ParallelRangeDataSource.UC_WINDOW_BYTES, uc.initialWindowBytes());
        assertEquals(true, uc.prefetchRanges());
        assertEquals(false, uc.adaptive());
        assertEquals(true, uc.rangeTuning());
        assertEquals(true, uc.refreshSlowRanges());

        assertNotNull(baidu);
        assertEquals(ParallelRangeDataSource.BAIDU_WINDOW_BYTES,
                baidu.initialWindowBytes());
        assertEquals(ParallelRangeDataSource.BAIDU_PARALLELISM, baidu.parallelism());
        assertEquals(true, baidu.prefetchRanges());
        assertEquals(true, baidu.rangeTuning());

        assertNotNull(generic);
        assertEquals(ParallelRangeDataSource.GENERIC_WINDOW_BYTES,
                generic.initialWindowBytes());
        assertEquals(ParallelRangeDataSource.GENERIC_PARALLELISM, generic.parallelism());
        assertEquals(true, generic.prefetchRanges());
        assertEquals(true, generic.rangeTuning());

        assertNotNull(aliPan);
        assertEquals(ParallelRangeDataSource.ALIPAN_MIN_WINDOW_BYTES,
                aliPan.minimumWindowBytes());
        assertEquals(ParallelRangeDataSource.ALIPAN_WINDOW_BYTES,
                aliPan.initialWindowBytes());
        assertEquals(ParallelRangeDataSource.ALIPAN_MAX_WINDOW_BYTES,
                aliPan.maximumWindowBytes());
        assertEquals(4, aliPan.parallelism());

        for (ParallelRangeDataSource.RangePolicy policy : new ParallelRangeDataSource.RangePolicy[]{
                aliPan}) {
            assertEquals(true, policy.adaptive());
            assertEquals(false, policy.rangeTuning());
            assertEquals(true, policy.strictContentRange());
            assertEquals(true, policy.refreshSlowRanges());
            assertEquals(true, policy.sequentialFallback());
            assertEquals(false, policy.prefetchRanges());
        }
        assertNull(ParallelRangeDataSource.RangePolicy.forProfile("ordinary-http"));
    }

    @Test
    public void exactRangeProbeExtractsTotalAndRejectsAmbiguousResponses() {
        java.util.Map<String, java.util.List<String>> valid = java.util.Map.of(
                "Content-Range", java.util.List.of("bytes 0-0/5368709120"));

        assertEquals(5L * 1024L * 1024L * 1024L,
                ParallelRangeDataSource.contentRangeTotal(valid, 0L, 1L));
        assertEquals(0L, ParallelRangeDataSource.contentRangeTotal(
                java.util.Map.of("Content-Range", java.util.List.of("bytes 0-1/99")),
                0L, 1L));
        assertEquals(0L, ParallelRangeDataSource.contentRangeTotal(
                java.util.Map.of("Content-Length", java.util.List.of("99")), 0L, 1L));
    }

    @Test
    public void detectsMaterialGainLossAndSlowRanges() {
        assertEquals(true, AdaptiveWindowController.significantGain(0L, 500_000L));
        assertEquals(true, AdaptiveWindowController.significantGain(500_000L, 550_000L));
        assertEquals(false, AdaptiveWindowController.significantGain(500_000L, 549_999L));
        assertEquals(false, AdaptiveWindowController.significantLoss(1_000_000L, 700_000L));
        assertEquals(true, AdaptiveWindowController.significantLoss(1_000_000L, 699_999L));
        assertEquals(false, ParallelRangeDataSource.shouldRefreshRange(
                2_000_000L, 1_000_000L, 1_000_000_000L));
        assertEquals(true, ParallelRangeDataSource.shouldRefreshRange(
                2_000_000L, 1_000_000L, 2_000_000_000L));
    }

    @Test
    public void urgentStartupIsBoundedBeforeRampingToLearnedTarget() {
        assertEquals(1, ParallelRangeDataSource.urgentRangeCount(1));
        assertEquals(4, ParallelRangeDataSource.urgentRangeCount(4));
        assertEquals(4, ParallelRangeDataSource.urgentRangeCount(32));
    }

    @Test
    public void cancellationNeverTriggersSequentialRecovery() {
        assertEquals(true, ParallelRangeDataSource.isCancellation(
                new InterruptedIOException("cancelled")));
        assertEquals(true, ParallelRangeDataSource.isCancellation(
                new IOException("wrapped", new InterruptedException("cancelled"))));
        assertEquals(false, ParallelRangeDataSource.isCancellation(
                new IOException("network failure")));
    }

    @Test
    public void rollsBackThenProbesAgainAfterSpeedRecovery() {
        AdaptiveWindowController controller =
                new AdaptiveWindowController(2L, 16L);

        assertEquals(AdaptiveWindowController.Action.NONE,
                controller.record(1_000_000L));
        assertEquals(AdaptiveWindowController.Action.GROW,
                controller.record(1_000_000L));
        assertEquals(4L, controller.windowBytes());

        assertEquals(AdaptiveWindowController.Action.ROLLBACK,
                controller.record(600_000L));
        assertEquals(2L, controller.windowBytes());

        // A low-bandwidth period establishes a new baseline. Once the cooldown expires, the
        // controller probes again instead of remaining permanently stuck at the minimum window.
        assertEquals(AdaptiveWindowController.Action.NONE,
                controller.record(600_000L));
        assertEquals(AdaptiveWindowController.Action.NONE,
                controller.record(600_000L));
        assertEquals(AdaptiveWindowController.Action.NONE,
                controller.record(600_000L));
        assertEquals(AdaptiveWindowController.Action.NONE,
                controller.record(600_000L));
        assertEquals(AdaptiveWindowController.Action.GROW,
                controller.record(600_000L));
        assertEquals(4L, controller.windowBytes());

        assertEquals(AdaptiveWindowController.Action.NONE,
                controller.record(900_000L));
        assertEquals(AdaptiveWindowController.Action.NONE,
                controller.record(900_000L));
        assertEquals(900_000L, controller.baselineBps());
        assertEquals(AdaptiveWindowController.Action.SHRINK,
                controller.record(500_000L));
        assertEquals(2L, controller.windowBytes());
    }

    @Test
    public void providerDefaultCanShrinkTowardItsSafeMinimum() {
        AdaptiveWindowController controller =
                new AdaptiveWindowController(4L, 16L, 16L);

        assertEquals(16L, controller.windowBytes());
        assertEquals(AdaptiveWindowController.Action.NONE,
                controller.record(1_000_000L));
        assertEquals(AdaptiveWindowController.Action.NONE,
                controller.record(1_000_000L));
        assertEquals(AdaptiveWindowController.Action.SHRINK,
                controller.record(600_000L));
        assertEquals(8L, controller.windowBytes());

        assertEquals(AdaptiveWindowController.Action.NONE,
                controller.record(600_000L));
        assertEquals(AdaptiveWindowController.Action.SHRINK,
                controller.record(300_000L));
        assertEquals(4L, controller.windowBytes());
    }

    @Test
    public void persistedWindowIsRestoredWithinHardwarePolicyBounds() {
        AdaptiveWindowController controller =
                new AdaptiveWindowController(4L, 8L, 16L);

        controller.restore(12L);
        assertEquals(12L, controller.windowBytes());
        controller.restore(99L);
        assertEquals(16L, controller.windowBytes());
        controller.restore(1L);
        assertEquals(4L, controller.windowBytes());
    }

    @Test
    public void adaptiveWindowRejectsInvalidBoundsAndIgnoresMissingSamples() {
        org.junit.Assert.assertThrows(IllegalArgumentException.class,
                () -> new AdaptiveWindowController(0L, 16L));
        org.junit.Assert.assertThrows(IllegalArgumentException.class,
                () -> new AdaptiveWindowController(4L, 3L, 16L));
        org.junit.Assert.assertThrows(IllegalArgumentException.class,
                () -> new AdaptiveWindowController(4L, 17L, 16L));
        AdaptiveWindowController controller = new AdaptiveWindowController(4L, 8L, 16L);
        assertEquals(AdaptiveWindowController.Action.NONE, controller.record(100L));
        assertEquals(AdaptiveWindowController.Action.NONE, controller.record(0L));
        assertEquals(AdaptiveWindowController.Action.NONE, controller.record(-1L));
        assertEquals(AdaptiveWindowController.Action.GROW, controller.record(100L));
        assertEquals(16L, controller.windowBytes());
        controller.reset();
        assertEquals(8L, controller.windowBytes());
        assertEquals(0L, controller.baselineBps());
        assertEquals(AdaptiveWindowController.Action.NONE, controller.record(50L));
        assertEquals(AdaptiveWindowController.Action.GROW, controller.record(50L));
    }

    @Test
    public void networkBatchEmitsOneActualTransferSample() {
        ConcurrentLinkedQueue<ParallelRangeDataSource.NetworkBatchSample> samples =
                new ConcurrentLinkedQueue<>();
        ParallelRangeDataSource.NetworkBatch batch =
                new ParallelRangeDataSource.NetworkBatch(2, samples);

        batch.firstProgress(100L);
        batch.success(1024L, 400L);
        assertEquals(0, samples.size());
        batch.firstProgress(200L);
        batch.success(2048L, 500L);

        ParallelRangeDataSource.NetworkBatchSample sample = samples.poll();
        assertNotNull(sample);
        assertEquals(3072L, sample.bytes());
        assertEquals(200L, sample.firstByteNanos());
        assertEquals(500L, sample.maxPartNanos());
        assertEquals(false, sample.failed());
        batch.failure();
        assertEquals(0, samples.size());
    }

    @Test
    public void networkBatchFailureEmitsOnlyOnce() {
        ConcurrentLinkedQueue<ParallelRangeDataSource.NetworkBatchSample> samples =
                new ConcurrentLinkedQueue<>();
        ParallelRangeDataSource.NetworkBatch batch =
                new ParallelRangeDataSource.NetworkBatch(2, samples);

        batch.failure();
        batch.failure();
        batch.success(1024L);

        assertEquals(1, samples.size());
        assertEquals(true, samples.poll().failed());
    }
}
