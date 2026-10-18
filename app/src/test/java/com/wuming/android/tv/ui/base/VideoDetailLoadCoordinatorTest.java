package com.wuming.android.tv.ui.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class VideoDetailLoadCoordinatorTest {

    @Test
    public void detailWaitsForPlaybackWithoutBlockingRendering() {
        VideoDetailLoadCoordinator<String> coordinator = new VideoDetailLoadCoordinator<>();

        assertNull(coordinator.onDetailReady("detail"));
        assertEquals("detail", coordinator.onPlaybackReady());
        assertNull(coordinator.onPlaybackReady());
    }

    @Test
    public void detailActivatesImmediatelyWhenPlaybackIsAlreadyReady() {
        VideoDetailLoadCoordinator<String> coordinator = new VideoDetailLoadCoordinator<>();

        assertNull(coordinator.onPlaybackReady());
        assertEquals("detail", coordinator.onDetailReady("detail"));
    }

    @Test
    public void newerDetailReplacesPendingActivation() {
        VideoDetailLoadCoordinator<String> coordinator = new VideoDetailLoadCoordinator<>();

        assertNull(coordinator.onDetailReady("old"));
        assertNull(coordinator.onDetailReady("new"));
        assertEquals("new", coordinator.onPlaybackReady());
    }

    @Test
    public void discardedDetailIsNotActivatedAfterNavigation() {
        VideoDetailLoadCoordinator<String> coordinator = new VideoDetailLoadCoordinator<>();

        assertNull(coordinator.onDetailReady("old"));
        coordinator.discardPending();
        assertNull(coordinator.onPlaybackReady());
    }
}
