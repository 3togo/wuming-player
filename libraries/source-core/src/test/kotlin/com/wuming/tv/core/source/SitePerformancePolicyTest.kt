package com.wuming.tv.core.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class SitePerformancePolicyTest {

    @Test
    fun speedSamplesConvertBytesAndGuardOverflow() {
        assertEquals(2_000_000L, SiteSpeedSamplePolicy.bytesPerSecond(4_000_000L, 2_000L))
        assertEquals(0L, SiteSpeedSamplePolicy.bytesPerSecond(0L, 2_000L))
        assertEquals(0L, SiteSpeedSamplePolicy.bytesPerSecond(1_000L, 0L))
        assertEquals((Long.MAX_VALUE / 2 / 4_000L) * 1_000L,
            SiteSpeedSamplePolicy.bytesPerSecond(Long.MAX_VALUE / 2, 4_000L))
        assertEquals(Long.MAX_VALUE,
            SiteSpeedSamplePolicy.bytesPerSecond(Long.MAX_VALUE, 1L))
    }

    @Test
    fun liveSpeedStaysSilentForTheFirstSecond() {
        assertEquals(0L, SiteSpeedSamplePolicy.liveBytesPerSecond(9_000L, 999L))
        assertEquals(9_000L, SiteSpeedSamplePolicy.liveBytesPerSecond(9_000L, 1_000L))
    }

    @Test
    fun outcomesSeparateDeadLinksFromUnmeasuredSuccesses() {
        assertEquals(SiteSpeedSamplePolicy.Outcome.DEAD_LINK,
            SiteSpeedSamplePolicy.outcome(0L, 10_000L))
        assertEquals(SiteSpeedSamplePolicy.Outcome.UNMEASURED_SUCCESS,
            SiteSpeedSamplePolicy.outcome(1_024L, SiteSpeedSamplePolicy.MIN_MEASURED_DURATION_MS - 1))
        assertEquals(SiteSpeedSamplePolicy.Outcome.MEASURED_SUCCESS,
            SiteSpeedSamplePolicy.outcome(1_024L, SiteSpeedSamplePolicy.MIN_MEASURED_DURATION_MS))
    }

    @Test
    fun flushIsThrottledToTheSaveInterval() {
        val now = TimeUnit.MINUTES.toMillis(10)
        val interval = SitePersistencePolicy.SAVE_INTERVAL_MS

        assertEquals(interval, SitePersistencePolicy.remainingMillis(now, now))
        assertEquals(1L, SitePersistencePolicy.remainingMillis(now, now - interval + 1))
        assertEquals(0L, SitePersistencePolicy.remainingMillis(now, now - interval))
        assertEquals(0L, SitePersistencePolicy.remainingMillis(now, now - interval - 1))
        assertEquals(interval, SitePersistencePolicy.remainingMillis(now, now + 1))
    }

    @Test
    fun scheduledFlushKeepsTheInitialDelay() {
        assertEquals(TimeUnit.SECONDS.toMillis(30), SitePersistencePolicy.INITIAL_SAVE_DELAY_MS)
    }

    @Test
    fun failedKeysAreRequeuedUntilTheRetryBudgetIsSpent() {
        assertTrue(SitePersistencePolicy.shouldRetry(0))
        assertTrue(SitePersistencePolicy.shouldRetry(SitePersistencePolicy.MAX_RETRY_FAILURES - 1))
        assertFalse(SitePersistencePolicy.shouldRetry(SitePersistencePolicy.MAX_RETRY_FAILURES))
    }

    @Test
    fun storedHistoryIsAdoptedOnlyByAnEmptyLiveRecord() {
        assertFalse(SitePersistencePolicy.hasHistory(0))
        assertTrue(SitePersistencePolicy.hasHistory(1))
        assertTrue(SitePersistencePolicy.shouldAdopt(0, 5))
        assertFalse(SitePersistencePolicy.shouldAdopt(3, 5))
        assertFalse(SitePersistencePolicy.shouldAdopt(0, 0))
    }
}
