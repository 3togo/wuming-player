package com.wuming.tv.core.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveBufferControllerTest {

    private val secondUs = AdaptiveBufferController.SECOND_US

    @Test
    fun smoothLoadingCapsAtBaseTargetWithoutGrowingReserve() {
        val controller = controller()
        var active = true
        var decision: AdaptiveBufferController.Decision? = null
        for (second in 1..16) {
            decision = controller.update(sample(second,
                (10_000L + second * 500L) * 1_000L,
                (second - 1L) * secondUs, false, 0L), active)
            active = decision.loading
        }
        assertEquals(20L * secondUs, decision!!.targetUs)
        assertEquals(true, decision.downloadMarginPermille >= 1_300)
        assertEquals(AdaptiveBufferController.Health.SMOOTH, decision.health)
    }

    @Test
    fun repeatedWeakFillRaisesReserveOnlyAfterHysteresis() {
        val controller = controller()
        var decision: AdaptiveBufferController.Decision? = null
        for (second in 1..9) {
            decision = controller.update(sample(second,
                (9_000L - second * 200L) * 1_000L,
                (second - 1L) * secondUs, false, 0L), true)
        }
        assertEquals(20L * secondUs, decision!!.targetUs)
        for (second in 10..11) {
            decision = controller.update(sample(second,
                (9_000L - second * 200L) * 1_000L,
                (second - 1L) * secondUs, false, 0L), true)
        }
        assertEquals(25L * secondUs, decision.targetUs)
        assertEquals(AdaptiveBufferController.Action.RAISE_WEAK, decision.action)
    }

    @Test
    fun oneRebufferAddsBoundedReserveOnlyOnce() {
        val controller = controller()
        val first = controller.update(sample(1, 2L * secondUs, 0L, true, 1_000L), true)
        val duplicate = controller.update(sample(2, 3L * secondUs, secondUs, true, 1_000L), true)
        assertEquals(30L * secondUs, first.targetUs)
        assertEquals(AdaptiveBufferController.Action.RAISE_REBUFFER, first.action)
        assertEquals(30L * secondUs, duplicate.targetUs)
        assertEquals(AdaptiveBufferController.Action.NONE, duplicate.action)
    }

    @Test
    fun sustainedSmoothnessDecaysTemporaryReserve() {
        val controller = controller()
        controller.update(sample(1, 10L * secondUs, 0L, true, 1_000L), true)
        var decision: AdaptiveBufferController.Decision? = null
        for (second in 2..42) {
            decision = controller.update(sample(second,
                (10_000L + (second - 1L) * 400L) * 1_000L,
                (second - 1L) * secondUs, false, 1_000L), true)
        }
        assertEquals(25L * secondUs, decision!!.targetUs)
    }

    @Test
    fun targetAndLowWaterFormStableLoadingHysteresis() {
        val controller = controller()
        val full = controller.update(sample(1, 20L * secondUs, 0L, false, 0L), true)
        val middle = controller.update(sample(2, 15L * secondUs, secondUs, false, 0L), false)
        val low = controller.update(sample(3, 10L * secondUs, 2L * secondUs, false, 0L), false)
        assertEquals(false, full.loading)
        assertEquals(false, middle.loading)
        assertEquals(true, low.loading)
    }

    @Test
    fun preferenceMappingStaysWithinConservativeHardBounds() {
        val minimum = AdaptiveBufferController.Config.fromPreference(1)
        val maximum = AdaptiveBufferController.Config.fromPreference(10)
        assertEquals(9L * secondUs, minimum.baseResumeUs)
        assertEquals(20L * secondUs, minimum.baseTargetUs)
        assertEquals(12L * secondUs, maximum.baseResumeUs)
        assertEquals(38L * secondUs, maximum.baseTargetUs)
        assertEquals(53L * secondUs, maximum.maximumTargetUs)
    }

    @Test
    fun recoveryReserveRaisesResumeWatermarkConservatively() {
        val controller = controller()
        val decision = controller.update(sample(1, 2L * secondUs, 0L, true, 1_000L), true)
        assertEquals(30L * secondUs, decision.targetUs)
        assertEquals(15L * secondUs, decision.resumeWatermarkUs)
    }

    @Test
    fun pauseFillsOnlyToAdaptiveTargetAndKeepsRecoveryReserve() {
        val controller = controller()
        controller.update(sample(1, 2L * secondUs, 0L, true, 1_000L), true)
        val filling = controller.update(sample(2, 18L * secondUs, 0L, false, 1_000L, false), true)
        val full = controller.update(sample(3, 30L * secondUs, 0L, false, 1_000L, false), true)
        assertEquals(30L * secondUs, filling.targetUs)
        assertEquals(true, filling.loading)
        assertEquals(false, full.loading)
        assertEquals(AdaptiveBufferController.Health.UNKNOWN, full.health)
    }

    @Test
    fun loaderOffPulseCannotLowerReserveUsingStaleSmoothness() {
        val controller = controller()
        controller.update(sample(1, 2L * secondUs, 0L, true, 1_000L), true)
        for (second in 2..7) {
            controller.update(sample(second,
                (10_000L + second * 500L) * 1_000L,
                (second - 1L) * secondUs, false, 1_000L), true)
        }
        var decision: AdaptiveBufferController.Decision? = null
        for (second in 8..48) {
            decision = controller.update(sample(second, 18L * secondUs,
                (second - 1L) * secondUs, false, 1_000L), false)
        }
        assertEquals(30L * secondUs, decision!!.targetUs)
    }

    @Test
    fun stalledPlayheadDoesNotFabricateDownloadedMedia() {
        val controller = controller()
        var decision: AdaptiveBufferController.Decision? = null
        for (second in 1..6) {
            decision = controller.update(sample(second,
                (10_000L + second * 200L) * 1_000L,
                0L, false, 0L), true)
        }
        assertEquals(200, decision!!.downloadMarginPermille)
        assertEquals(AdaptiveBufferController.Health.WEAK, decision.health)
        assertEquals(20L * secondUs, decision.targetUs)
    }

    @Test
    fun expiredWeakEvidenceDoesNotCombineAcrossLongIdleGap() {
        val controller = controller()
        for (second in 1..6) {
            controller.update(sample(second,
                (10_000L - second * 200L) * 1_000L,
                (second - 1L) * secondUs, false, 0L), true)
        }
        for (second in 7..40) {
            controller.update(sample(second, 9L * secondUs,
                (second - 1L) * secondUs, false, 0L), false)
        }
        var decision: AdaptiveBufferController.Decision? = null
        for (second in 41..46) {
            decision = controller.update(sample(second,
                (9_000L - (second - 40L) * 200L) * 1_000L,
                (second - 1L) * secondUs, false, 0L), true)
        }
        assertEquals(20L * secondUs, decision!!.targetUs)
    }

    @Test
    fun zeroDownloadIsWeakEvidenceInsteadOfAnUnknownMeasurement() {
        val controller = controller()
        var decision: AdaptiveBufferController.Decision? = null
        for (second in 1..11) {
            decision = controller.update(sample(second,
                (19L - second) * secondUs,
                (second - 1L) * secondUs, false, 0L), true)
        }
        assertEquals(0, decision!!.downloadMarginPermille)
        assertEquals(AdaptiveBufferController.Health.WEAK, decision.health)
        assertEquals(25L * secondUs, decision.targetUs)
    }

    @Test
    fun aZeroRateSampleIsIncludedWhenDownloadRecovers() {
        val controller = controller()
        controller.update(sample(1, 10L * secondUs, 0L, false, 0L), true)
        controller.update(sample(2, 9L * secondUs, secondUs, false, 0L), true)
        val decision = controller.update(sample(3, 10L * secondUs, 2L * secondUs, false, 0L), true)
        assertEquals(500, decision.downloadMarginPermille)
    }

    private fun controller(): AdaptiveBufferController {
        return AdaptiveBufferController(AdaptiveBufferController.Config(10L * secondUs, 20L * secondUs, 40L * secondUs))
    }

    private fun sample(second: Int, bufferedUs: Long, positionUs: Long,
                       rebuffering: Boolean, lastRebufferRealtimeMs: Long): AdaptiveBufferController.Sample {
        return sample(second, bufferedUs, positionUs, rebuffering, lastRebufferRealtimeMs, true)
    }

    private fun sample(second: Int, bufferedUs: Long, positionUs: Long,
                       rebuffering: Boolean, lastRebufferRealtimeMs: Long, playWhenReady: Boolean): AdaptiveBufferController.Sample {
        return AdaptiveBufferController.Sample(second * 1_000L, bufferedUs, positionUs, 1f, playWhenReady, rebuffering, lastRebufferRealtimeMs)
    }
}
