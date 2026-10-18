package com.wuming.android.tv.ui.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BufferingCountdownTest {

    @Test
    public void estimatesRemainingTimeFromObservedBufferGrowth() {
        BufferingCountdown countdown = new BufferingCountdown();
        countdown.start(1_000L);

        assertEquals(60L, countdown.update(1_000L, 0L, 8_000L).etaSeconds());
        BufferingCountdown.Status status = countdown.update(2_000L, 1_000L, 8_000L);

        assertTrue(status.hasEstimate());
        assertEquals(7L, status.etaSeconds());
        assertEquals(1L, status.elapsedSeconds());
    }

    @Test
    public void stalledBufferFallsBackToProvisionalCountdown() {
        BufferingCountdown countdown = new BufferingCountdown();
        countdown.update(0L, 0L, 8_000L);
        assertTrue(countdown.update(1_000L, 1_000L, 8_000L).hasEstimate());

        countdown.update(2_000L, 1_000L, 8_000L);
        assertEquals(57L, countdown.update(3_000L, 1_000L, 8_000L).etaSeconds());
    }

    @Test
    public void provisionalEstimateCountsDownEverySecond() {
        BufferingCountdown countdown = new BufferingCountdown();

        assertEquals(60L, countdown.update(1_000L, 0L, 8_000L).etaSeconds());
        assertEquals(59L, countdown.update(2_000L, 0L, 8_000L).etaSeconds());
        assertEquals(58L, countdown.update(3_000L, 0L, 8_000L).etaSeconds());
    }

    @Test
    public void resetStartsANewWaitTimer() {
        BufferingCountdown countdown = new BufferingCountdown();
        countdown.update(1_000L, 0L, 3_000L);
        countdown.reset();

        BufferingCountdown.Status status = countdown.update(10_000L, 3_000L, 3_000L);
        assertEquals(0L, status.elapsedSeconds());
        assertEquals(0L, status.etaSeconds());
        assertTrue(status.finishing());
    }
}
