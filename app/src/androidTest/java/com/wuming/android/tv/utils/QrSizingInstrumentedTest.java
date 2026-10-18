package com.wuming.android.tv.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Guards the QR fit invariant behind the leanback clipping regression: after the
 * post-layout correction, the dialog window must fit the display, otherwise the
 * bottom rows of the code are clipped and a phone camera cannot read it.
 */
@RunWith(AndroidJUnit4.class)
public class QrSizingInstrumentedTest {

    @Test
    public void fittedSquareLeavesTheWindowInsideTheDisplay() {
        int maxWindow = QrSizing.maxWindowHeightPx(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        int[][] probes = {{1000, 400}, {1400, 300}, {700, 500}, {900, 900}};
        for (int[] probe : probes) {
            int windowHeight = probe[0];
            int qrHeight = probe[1];
            int result = QrSizing.fitSquarePx(windowHeight, qrHeight, maxWindow, qrHeight);
            int chrome = windowHeight - qrHeight;
            assertTrue("qr " + result + " + chrome " + chrome + " exceeds " + maxWindow,
                    result + chrome <= maxWindow || result == QrSizing.minPx());
            // fitSquarePx() clamps with minPx() as a floor, so a probe whose preferred size is
            // below the scannable minimum legitimately ends at minPx() too, not only at maxPx().
            assertTrue(result <= qrHeight
                    || result == QrSizing.maxPx()
                    || result == QrSizing.minPx());
        }
    }

    @Test
    public void estimateStaysInsideTheConfiguredBounds() {
        assertEquals(QrSizing.maxPx(),
                QrSizing.clampPx(QrSizing.maxPx() + 10_000));
        assertEquals(QrSizing.minPx(), QrSizing.clampPx(1));
    }
}
