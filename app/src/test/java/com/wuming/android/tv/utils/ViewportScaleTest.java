package com.wuming.android.tv.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ViewportScaleTest {

    @Test
    public void mobileUsesAStableDesignWidthForEachOrientation() {
        assertEquals(360f, ViewportScale.mobileDesignWidth(false), 0.0001f);
        assertEquals(600f, ViewportScale.mobileDesignWidth(true), 0.0001f);
    }

    @Test
    public void normalTvScalesEquallyOnBothAxes() {
        assertTrue(ViewportScale.isWidthConstrained(1920, 1080, 960, 540));
        assertEquals(1f, ViewportScale.uniformScale(1920, 1080, 1920, 1080), 0.0001f);
        assertEquals(24, ViewportScale.scaleTvPx(24, 1920, 1080));
        assertEquals(48, ViewportScale.scaleTvPx(24, 3840, 2160));
    }

    @Test
    public void ultrawideViewportIsLimitedByHeight() {
        assertFalse(ViewportScale.useWidthForTv(2560, 1080));
        assertEquals(1f, ViewportScale.uniformScale(2560, 1080, 1920, 1080), 0.0001f);
        assertEquals(24, ViewportScale.scaleTvPx(24, 2560, 1080));
    }

    @Test
    public void shortViewportIsLimitedByHeight() {
        assertFalse(ViewportScale.isWidthConstrained(1920, 800, 960, 540));
        assertEquals(800f / 1080f,
                ViewportScale.uniformScale(1920, 800, 1920, 1080), 0.0001f);
    }

    @Test
    public void narrowViewportIsLimitedByWidth() {
        assertTrue(ViewportScale.useWidthForTv(1280, 1080));
        assertEquals(1280f / 1920f,
                ViewportScale.uniformScale(1280, 1080, 1920, 1080), 0.0001f);
    }

    @Test
    public void designDpScalesByDensityNotByRawPixelCount() {
        assertEquals(36, ViewportScale.scaleTvDp(18, 1920, 1080));
        assertEquals(72, ViewportScale.scaleTvDp(18, 3840, 2160));
        assertEquals(24, ViewportScale.scaleTvDp(18, 1280, 720));
        assertEquals(36, ViewportScale.scaleTvDp(18, 2560, 1080));
    }

    @Test
    public void designDpAndReferencePixelScalesDifferByThePixelDensity() {
        assertEquals(2f, ViewportScale.scaleTvDp(18, 1920, 1080)
                / (float) ViewportScale.scaleTvPx(18, 1920, 1080), 0.0001f);
    }
}
