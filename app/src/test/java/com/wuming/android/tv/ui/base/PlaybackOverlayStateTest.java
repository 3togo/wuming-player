package com.wuming.android.tv.ui.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PlaybackOverlayStateTest {

    @Test
    public void infoPartsIgnoreMissingValuesAndUseStableSeparator() {
        assertEquals("1920x1080 | H264 | 2.1 MB/s",
                PlaybackOverlayState.join("1920x1080", "H264", "", "2.1 MB/s"));
    }

    @Test
    public void infoTextIsHiddenOutsideFullscreenEvenWhenEnabled() {
        assertTrue(PlaybackOverlayState.shouldShowVideoInfo(0, true));
        assertTrue(PlaybackOverlayState.shouldShowVideoInfo(1, true));
        assertFalse(PlaybackOverlayState.shouldShowVideoInfo(0, false));
        assertFalse(PlaybackOverlayState.shouldShowVideoInfo(1, false));
    }

    @Test
    public void infoTextStaysHiddenWhenSettingDisablesIt() {
        assertFalse(PlaybackOverlayState.shouldShowVideoInfo(2, true));
        assertFalse(PlaybackOverlayState.shouldShowVideoInfo(3, true));
        assertFalse(PlaybackOverlayState.shouldShowVideoInfo(2, false));
        assertFalse(PlaybackOverlayState.shouldShowVideoInfo(3, false));
    }
}
