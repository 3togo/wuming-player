package com.wuming.android.tv.player.exo;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PlaybackHealthMonitorTest {

    @Test
    public void freshHealthCarriesMarginAndMonotonicRebufferEvidence() {
        PlaybackHealthMonitor monitor = new PlaybackHealthMonitor();
        monitor.record(1_000L, 1_250, 40L);
        monitor.record(1_100L, 1_300, 30L);

        PlaybackHealthMonitor.Snapshot snapshot =
                monitor.snapshot(1_200L);

        assertEquals(1_300, snapshot.marginPermille());
        assertEquals(40L, snapshot.rebufferId());
    }

    @Test
    public void staleHealthIsNotAppliedToAnotherTransfer() {
        PlaybackHealthMonitor monitor = new PlaybackHealthMonitor();
        monitor.record(1_000L, 1_250, 40L);

        PlaybackHealthMonitor.Snapshot snapshot = monitor.snapshot(
                1_000L + PlaybackHealthMonitor.FRESH_MILLIS + 1L);

        assertEquals(0, snapshot.marginPermille());
        assertEquals(0L, snapshot.rebufferId());
    }

    @Test
    public void separatePlayersDoNotShareHealthOrClearEachOthersEvidence() {
        PlaybackHealthMonitor first = new PlaybackHealthMonitor();
        PlaybackHealthMonitor second = new PlaybackHealthMonitor();
        first.record(1_000L, 1_250, 40L);
        assertEquals(0, second.snapshot(1_000L).marginPermille());
        second.record(1_000L, 900, 20L);
        first.clear();
        assertEquals(900, second.snapshot(1_000L).marginPermille());
        assertEquals(20L, second.snapshot(1_000L).rebufferId());
    }
}
