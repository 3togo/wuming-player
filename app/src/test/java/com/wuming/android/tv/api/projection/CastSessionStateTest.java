package com.wuming.android.tv.api.projection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CastSessionStateTest {

    @Test
    public void idleStateIsNotActive() {
        ProjectionManager.CastSessionState state = ProjectionManager.CastSessionState.idle();

        assertEquals(ProjectionManager.CastState.IDLE, state.state());
        assertEquals(-1, state.episodeIndex());
        assertFalse(state.isActive());
    }

    @Test
    public void playingStateCarriesConfirmedEpisode() {
        ProjectionManager.CastSessionState state = new ProjectionManager.CastSessionState(
                ProjectionManager.CastState.PLAYING, 1, "02", "Living room TV");

        assertTrue(state.isActive());
        assertEquals(1, state.episodeIndex());
        assertEquals("02", state.episodeName());
        assertEquals("Living room TV", state.deviceName());
    }
}
