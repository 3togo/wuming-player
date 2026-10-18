package com.wuming.tv.core.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveRangeConcurrencyTest {
    private fun batches(c: AdaptiveRangeConcurrency, rate: Long) {
        c.complete(rate, 1000); c.complete(rate, 1000)
    }

    @Test fun increasesOnlyAfterTwoAggregateSamples() {
        val c = AdaptiveRangeConcurrency()
        assertEquals(1, c.connections())
        c.complete(100, 1000); assertEquals(1, c.connections())
        c.complete(100, 1000); assertEquals(2, c.connections())
        batches(c, 200); assertEquals(4, c.connections())
        batches(c, 400); assertEquals(6, c.connections())
    }

    @Test fun rollbackOnPlateauDoesNotKeepProbingRejectedLevel() {
        val c = AdaptiveRangeConcurrency()
        batches(c, 100); batches(c, 105)
        assertEquals(1, c.connections())
        batches(c, 1000); assertEquals(1, c.connections())
    }

    @Test fun rejectionReturnsToLastMeasuredLevelAndHonorsDelay() {
        val c = AdaptiveRangeConcurrency()
        batches(c, 100); batches(c, 200)
        c.rejected(1000, 12000)
        assertEquals(2, c.connections()); assertEquals(13000, c.notBeforeMs())
        batches(c, 200); assertEquals(2, c.connections())
        c.rejected(20000, 0)
        assertEquals(1, c.connections()); assertEquals(25000, c.notBeforeMs())
    }

    @Test fun weightsBytesByElapsedTimeInsteadOfAveragingWorkerSpeeds() {
        val c = AdaptiveRangeConcurrency()
        batches(c, 100)
        c.complete(200, 1000); c.complete(100, 4000)
        assertEquals(1, c.connections())
    }

    @Test fun maximumAndInvalidSamplesAreBounded() {
        val c = AdaptiveRangeConcurrency()
        c.complete(0, 1); c.complete(100, 0); assertEquals(1, c.connections())
        for (i in 0 until 30) batches(c, c.connections() * 100L)
        assertEquals(16, c.connections())
        c.rejected(0, 0); assertEquals(14, c.connections())
    }
}
