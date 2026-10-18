package com.wuming.android.tv.utils;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GridSpecCalculatorTest {

    @Test
    public void dimensionsScaleLinearlyWithScreenWidth() {
        int[] base = GridSpecCalculator.calculate(1000, 400, 3, 0, 0.75f, 100, 0.022f, 0.022f);
        int[] doubled = GridSpecCalculator.calculate(2000, 800, 3, 0, 0.75f, 200, 0.022f, 0.022f);

        assertArrayEquals(new int[]{base[0] * 2, base[1] * 2, base[2] * 2, base[3] * 2}, doubled);
    }

    @Test
    public void representativePhoneWidthsProduceValidSpecs() {
        for (int width : new int[]{720, 1080, 1600, 2400}) {
            int columns = width > 1080 ? 5 : 3;
            int[] spec = GridSpecCalculator.calculate(width, 0, columns, 0, 0.75f, width / 10, 0.022f, 0.022f);

            assertTrue(spec[0] > 0);
            assertTrue(spec[1] > spec[3]);
            assertTrue(spec[2] >= 0);
            assertTrue(spec[3] > spec[0]);
        }
    }

    @Test
    public void invalidInputsAreClampedToSafeValues() {
        int[] spec = GridSpecCalculator.calculate(0, -10, 0, -20, 0f, -30, -1f, -1f);

        assertEquals(1, spec[0]);
        assertEquals(1, spec[1]);
        assertEquals(0, spec[2]);
        assertEquals(1, spec[3]);
    }

    @Test
    public void broadPhoneTabletAndTvWidthsRemainProportional() {
        for (int width : new int[]{320, 480, 720, 1080, 1440, 2160, 3840}) {
            int columns = width < 600 ? 1 : width < 1200 ? 3 : 5;
            int[] spec = GridSpecCalculator.calculate(width, width / 5, columns, width / 50,
                    0.75f, width / 25, 0.022f, 0.022f);
            assertTrue(spec[0] >= 1);
            assertTrue(spec[0] <= width);
            assertEquals((int) (spec[0] / 0.75f), spec[3]);
            assertEquals(spec[3] + width / 25, spec[1]);
        }
    }

    @Test
    public void fontScalingChangesOnlyTextContributionNotGridWidthOrArtwork() {
        int[] normal = GridSpecCalculator.calculate(1080, 216, 4, 0,
                0.75f, 24, 0.022f, 0.022f);
        int[] largeText = GridSpecCalculator.calculate(1080, 216, 4, 0,
                0.75f, 48, 0.022f, 0.022f);

        assertEquals(normal[0], largeText[0]);
        assertEquals(normal[2], largeText[2]);
        assertEquals(normal[3], largeText[3]);
        assertEquals(24, largeText[1] - normal[1]);
    }

    @Test
    public void landscapeRailIsReservedAndGridRetainsHorizontalGaps() {
        int screenWidth = 2048;
        int railWidth = 230;
        int columns = 5;

        int[] spec = GridSpecCalculator.calculate(screenWidth, railWidth, columns, 0,
                0.75f, 80, 0.022f, 0.022f);

        int occupiedWidth = columns * (spec[0] + spec[2] * 2);
        assertTrue(spec[2] > 0);
        assertTrue(occupiedWidth <= screenWidth - railWidth);
    }
}
