package com.wuming.tv.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeProbeOptimizerTest {

    private val default = RangeProbeOptimizer.Setting(4, 2L)
    private val bounds = RangeProbeOptimizer.Bounds(2, 8, 1L, 8L, 4L, 32L)

    @Test
    fun candidateNeverReplacesPersistedBestBeforeItWins() {
        val optimizer = RangeProbeOptimizer(default, bounds, null)

        assertEquals(RangeProbeOptimizer.Action.NONE, optimizer.recordBatch(100L, 1L).action)
        val probe = optimizer.recordBatch(100L, 2L)

        assertEquals(RangeProbeOptimizer.Action.PROBE, probe.action)
        assertEquals(RangeProbeOptimizer.Setting(5, 2L), probe.setting)
        assertEquals(default.ranges, probe.profile!!.ranges)
        assertEquals(default.rangeBytes, probe.profile.rangeBytes)
        assertEquals(default, optimizer.bestSetting())
    }

    @Test
    fun twoMateriallyFasterCandidateBatchesCommit() {
        val optimizer = RangeProbeOptimizer(default, bounds, null)
        optimizer.recordBatch(100L, 1L)
        optimizer.recordBatch(100L, 2L)

        assertEquals(RangeProbeOptimizer.Action.NONE, optimizer.recordBatch(120L, 3L).action)
        val commit = optimizer.recordBatch(120L, 4L)

        assertEquals(RangeProbeOptimizer.Action.COMMIT, commit.action)
        assertEquals(RangeProbeOptimizer.Setting(5, 2L), optimizer.bestSetting())
        assertEquals(5, commit.profile!!.ranges)
        assertEquals(RangeProbeOptimizer.ACCEPTED_PROBE_COOLDOWN, commit.profile.probeCooldown)
    }

    @Test
    fun fasterCandidateRollsBackWhenFirstByteLatencyRegresses() {
        val optimizer = RangeProbeOptimizer(default, bounds, null)
        optimizer.recordBatch(observation(100L, 100L, 1_300, 10L), 1L)
        optimizer.recordBatch(observation(100L, 100L, 1_300, 10L), 2L)

        val rollback = optimizer.recordBatch(observation(130L, 151L, 1_300, 10L), 3L)

        assertEquals(RangeProbeOptimizer.Action.ROLLBACK, rollback.action)
        assertEquals(default, optimizer.bestSetting())
    }

    @Test
    fun fasterCandidateRollsBackWhenItIntroducesRebuffering() {
        val optimizer = RangeProbeOptimizer(default, bounds, null)
        optimizer.recordBatch(observation(100L, 100L, 1_300, 10L), 1L)
        optimizer.recordBatch(observation(100L, 100L, 1_300, 10L), 2L)

        val rollback = optimizer.recordBatch(observation(130L, 100L, 1_300, 11L), 3L)

        assertEquals(RangeProbeOptimizer.Action.ROLLBACK, rollback.action)
        assertEquals(default, optimizer.bestSetting())
    }

    @Test
    fun healthierCandidateCanCommitWithoutTenPercentRawGain() {
        val optimizer = RangeProbeOptimizer(default, bounds, null)
        optimizer.recordBatch(observation(100L, 100L, 1_100, 10L), 1L)
        optimizer.recordBatch(observation(100L, 100L, 1_100, 10L), 2L)
        optimizer.recordBatch(observation(105L, 100L, 1_300, 10L), 3L)

        val commit = optimizer.recordBatch(observation(105L, 100L, 1_300, 10L), 4L)

        assertEquals(RangeProbeOptimizer.Action.COMMIT, commit.action)
        assertEquals(RangeProbeOptimizer.Setting(5, 2L), optimizer.bestSetting())
    }

    @Test
    fun oneSharpLossRollsBackWithoutOverwritingBest() {
        val optimizer = RangeProbeOptimizer(default, bounds, null)
        optimizer.recordBatch(100L, 1L)
        optimizer.recordBatch(100L, 2L)

        val rollback = optimizer.recordBatch(60L, 3L)

        assertEquals(RangeProbeOptimizer.Action.ROLLBACK, rollback.action)
        assertEquals(default, rollback.setting)
        assertEquals(default.ranges, rollback.profile!!.ranges)
        assertEquals(default.rangeBytes, rollback.profile.rangeBytes)
        assertEquals(RangeProbeOptimizer.REJECTED_PROBE_COOLDOWN, rollback.profile.probeCooldown)
        assertFalse(optimizer.probing())
    }

    @Test
    fun incompleteCandidateRollsBackAndKeepsShortCooldown() {
        val optimizer = RangeProbeOptimizer(default, bounds, null)
        optimizer.recordBatch(100L, 1L)
        optimizer.recordBatch(100L, 2L)
        assertTrue(optimizer.probing())

        val profile = optimizer.finish(3L)!!

        assertEquals(default, optimizer.setting())
        assertFalse(optimizer.probing())
        assertEquals(2, profile.probeCooldown)
        assertEquals(3L + RangeProbeOptimizer.INCOMPLETE_PROBE_COOLDOWN_MILLIS, profile.probeNotBefore)
    }

    @Test
    fun repeatedShortOpensDoNotConsumePersistedCooldown() {
        val rollbackAt = 10_000L
        val persisted = RangeTuningProfile(
            4, 2L, 100L, 3, rollbackAt, RangeProbeOptimizer.AXIS_COUNT,
            1, 1, RangeProbeOptimizer.REJECTED_PROBE_COOLDOWN,
            rollbackAt + RangeProbeOptimizer.REJECTED_PROBE_COOLDOWN_MILLIS, 4L)

        for (open in 0 until 12) {
            val now = rollbackAt + open + 1L
            val optimizer = RangeProbeOptimizer(default, bounds, persisted)
            optimizer.recordBatch(105L, now)
            assertEquals(RangeProbeOptimizer.Action.NONE, optimizer.recordBatch(105L, now + 1L).action)
            val next = optimizer.finish(now + 2L)
            assertNull(next)
            assertEquals(default, optimizer.setting())
        }
    }

    @Test
    fun completedMetadataReadDoesNotInflatePersistedConfidence() {
        val persisted = RangeTuningProfile(
            4, 2L, 100L, 3, 1L, RangeProbeOptimizer.AXIS_COUNT,
            1, 1, RangeProbeOptimizer.REJECTED_PROBE_COOLDOWN,
            Long.MAX_VALUE, 1L)
        val optimizer = RangeProbeOptimizer(default, bounds, persisted)

        optimizer.recordBatch(110L, 2L)

        assertNull(optimizer.finish(3L))
        assertEquals(default, optimizer.bestSetting())
    }

    @Test
    fun cooldownExpiresByTimeAndAllowsNextProbe() {
        val deadline = 20_000L
        val persisted = RangeTuningProfile(
            4, 2L, 100L, 3, 1L, RangeProbeOptimizer.AXIS_COUNT,
            1, 1, RangeProbeOptimizer.REJECTED_PROBE_COOLDOWN, deadline, 1L)
        val optimizer = RangeProbeOptimizer(default, bounds, persisted)

        optimizer.recordBatch(105L, deadline)
        assertEquals(RangeProbeOptimizer.Action.PROBE, optimizer.recordBatch(105L, deadline + 1L).action)
    }

    @Test
    fun failedCandidateRestoresPersistedBest() {
        val persisted = RangeTuningProfile(
            4, 2L, 100L, 3, 1L, RangeProbeOptimizer.AXIS_COUNT,
            1, 1, 0)
        val optimizer = RangeProbeOptimizer(default, bounds, persisted)
        optimizer.recordBatch(100L, 2L)
        optimizer.recordBatch(100L, 3L)

        val afterFailure = optimizer.fail(4L)!!

        assertEquals(default, optimizer.setting())
        assertEquals(4, afterFailure.ranges)
        assertEquals(2L, afterFailure.rangeBytes)
    }

    @Test
    fun finishingCommittedTransferDoesNotClearKnownGoodProfile() {
        val optimizer = RangeProbeOptimizer(default, bounds, null)
        optimizer.recordBatch(100L, 1L)
        optimizer.recordBatch(100L, 2L)
        optimizer.recordBatch(120L, 3L)
        val commit = optimizer.recordBatch(120L, 4L)

        assertEquals(RangeProbeOptimizer.Action.COMMIT, commit.action)
        assertNull(optimizer.finish(5L))
        assertEquals(commit.setting, optimizer.bestSetting())
    }

    @Test
    fun failedUnprovenDefaultDoesNotCreateAProfile() {
        val optimizer = RangeProbeOptimizer(default, bounds, null)
        assertNull(optimizer.fail(1L))
    }

    private fun observation(bps: Long, latency: Long, margin: Int, rebufferId: Long): RangeProbeOptimizer.Observation {
        return RangeProbeOptimizer.Observation(bps, latency, margin, rebufferId)
    }
}
