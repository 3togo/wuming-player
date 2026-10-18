package com.wuming.android.tv.ui.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.wuming.android.tv.bean.History;
import com.wuming.android.tv.player.PlayerManager;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class SkipMarkerControllerTest {
    @Test
    public void tvAdjustmentsNormalizeUnsetMarkersAndNeverGoBelowZero() {
        Host host = new Host();
        SkipMarkerController controller = new SkipMarkerController(host);
        controller.onOpeningAdd();
        controller.onEndingSub();
        assertEquals(1000, host.history.getOpening());
        assertEquals(0, host.history.getEnding());
        controller.onOpeningSub();
        controller.onOpeningSub();
        controller.onEndingAdd();
        assertEquals(0, host.history.getOpening());
        assertEquals(1000, host.history.getEnding());
    }

    @Test
    public void mobileResetsRefreshControlsBeforeApplyingMarker() {
        Host host = new Host();
        SkipMarkerController controller = new SkipMarkerController(host, () -> host.events.add("interaction"));
        assertTrue(controller.onOpeningReset());
        assertTrue(controller.onEndingReset());
        assertEquals(List.of("interaction", "opening:0", "interaction", "ending:0"), host.events);
    }

    @Test
    public void tvAdjustmentsBeforeHistoryLoadsDoNothing() {
        Host host = new Host();
        host.history = null;
        SkipMarkerController controller = new SkipMarkerController(host);
        controller.onOpeningAdd();
        controller.onOpeningSub();
        controller.onEndingAdd();
        controller.onEndingSub();
        assertTrue(host.events.isEmpty());
    }

    private static class Host implements SkipMarkerController.Host {
        History history = new History();
        final List<String> events = new ArrayList<>();

        @Override public PlayerManager getPlayerManager() {
            throw new AssertionError("Reset and adjustment must not query the player");
        }
        @Override public History getHistory() { return history; }
        @Override public void setOpening(long value) {
            events.add("opening:" + value);
            history.setOpening(value);
        }
        @Override public void setEnding(long value) {
            events.add("ending:" + value);
            history.setEnding(value);
        }
    }
}
