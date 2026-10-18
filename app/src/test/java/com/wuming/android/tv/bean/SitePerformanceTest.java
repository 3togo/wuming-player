package com.wuming.android.tv.bean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SitePerformanceTest {

    @Test
    public void successAndFailuresUpdateAllRates() {
        SitePerformance performance = new SitePerformance("site");

        performance.recordSuccess(2_000_000);
        performance.recordSuccess(4_000_000);
        performance.recordDeadLink();
        performance.recordFailure();

        assertEquals(4, performance.getTotalRequests());
        assertEquals(2, performance.getSuccessCount());
        assertEquals(1, performance.getDeadLinkCount());
        assertEquals(1, performance.getFailureCount());
        assertEquals(50.0, performance.getSuccessRate(), 0.0);
        assertEquals(25.0, performance.getDeadLinkRate(), 0.0);
        assertEquals(25.0, performance.getFailureRate(), 0.0);
        assertFalse(performance.isReliable());
    }

    @Test
    public void averageSpeedUsesOnlyPositiveSamples() {
        SitePerformance performance = new SitePerformance("site");

        performance.recordSuccess(1_000_000);
        performance.recordSuccess(3_000_000);
        performance.recordSuccess(0);

        assertEquals(2, performance.getSpeedSampleCount());
        assertEquals(2_000_000.0, performance.getAvgSpeed(), 0.0);
        assertEquals(3_000_000, performance.getLastSpeed());
        assertTrue(performance.getFormattedSpeed().endsWith("MB/s"));
    }

    @Test
    public void performanceScoreCombinesReliabilityAndCappedSpeed() {
        SitePerformance performance = new SitePerformance("site");
        performance.recordSuccess(20_000_000);

        assertEquals(1.0, performance.getReliabilityScore(), 0.0);
        assertEquals(1.0, performance.getPerformanceScore(), 0.0);
        assertTrue(performance.isReliable());
    }

    @Test
    public void snapshotIsAStablePersistenceCopy() {
        SitePerformance performance = new SitePerformance("site");
        performance.recordSuccess(2_000_000);

        SitePerformance snapshot = performance.snapshot();
        performance.recordFailure();

        assertEquals("site", snapshot.getSiteKey());
        assertEquals(1, snapshot.getTotalRequests());
        assertEquals(1, snapshot.getSuccessCount());
        assertEquals(0, snapshot.getFailureCount());
        assertEquals(2, performance.getTotalRequests());
    }
}
