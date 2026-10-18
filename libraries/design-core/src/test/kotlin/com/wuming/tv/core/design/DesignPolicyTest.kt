package com.wuming.tv.core.design

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class DesignPolicyTest {
    @Test fun persistedIdsAndUnknownValuesRemainCompatible() {
        assertEquals(Appearance.SYSTEM, Appearance.fromId(-1))
        assertEquals(Appearance.LIGHT, Appearance.fromId(1))
        assertEquals(Appearance.DARK, Appearance.fromId(2))
        assertEquals(ColorTheme.CLASSIC, ColorTheme.fromId(Int.MAX_VALUE))
        assertEquals(ColorTheme.AURORA, ColorTheme.fromId(1))
        for (theme in ColorTheme.entries) {
            assertEquals(ThemePolicy.palette(theme, Appearance.DARK, false), ThemePolicy.palette(theme, Appearance.SYSTEM, true))
            assertEquals(ThemePolicy.palette(theme, Appearance.LIGHT, true), ThemePolicy.palette(theme, Appearance.SYSTEM, false))
        }
    }

    @Test fun textAndFocusRemainLegibleAcrossEveryPalette() {
        for (theme in ColorTheme.entries) for (appearance in listOf(Appearance.LIGHT, Appearance.DARK)) {
            val p = ThemePolicy.palette(theme, appearance, false)
            for (surface in listOf(p.background, p.surface, p.surfaceRaised, p.tile, p.selection)) {
                for (text in listOf(p.text, p.mutedText, p.subtleText)) {
                    assertTrue("$theme $appearance text contrast ${contrast(text, surface)}", contrast(text, surface) >= 4.5)
                }
                assertTrue("$theme $appearance focus contrast", contrast(p.accent, surface) >= 3.0)
            }
            assertTrue("$theme $appearance filled action contrast", contrast(p.onAccent, p.accentStrong) >= 4.5)
        }
    }

    @Test fun layoutHandlesNarrowUnmeasuredAndUltrawideWindows() {
        assertEquals(WindowSize.COMPACT, LayoutPolicy.windowSize(599))
        assertEquals(WindowSize.MEDIUM, LayoutPolicy.windowSize(600))
        assertEquals(WindowSize.EXPANDED, LayoutPolicy.windowSize(1000))
        assertEquals(0, LayoutPolicy.desktopRailWidth(-1))
        assertEquals(100, LayoutPolicy.desktopRailWidth(100))
        assertEquals(176, LayoutPolicy.desktopRailWidth(800))
        assertEquals(256, LayoutPolicy.desktopRailWidth(3840))
        assertEquals(0, LayoutPolicy.formWidth(Int.MIN_VALUE))
        assertEquals(320, LayoutPolicy.formWidth(360))
        assertEquals(640, LayoutPolicy.formWidth(Int.MAX_VALUE))
        assertEquals(3, LayoutPolicy.MOBILE_PORTRAIT_COLUMNS)
        assertEquals(5, LayoutPolicy.MOBILE_LANDSCAPE_COLUMNS)
        assertEquals(5, LayoutPolicy.TV_COLUMNS)
        assertEquals(0.75f, LayoutPolicy.POSTER_WIDTH_TO_HEIGHT, 0f)
    }

    private fun contrast(first: Int, second: Int): Double {
        fun luminance(color: Int): Double {
            fun channel(shift: Int): Double {
                val v = ((color ushr shift) and 255) / 255.0
                return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
            }
            return channel(16) * 0.2126 + channel(8) * 0.7152 + channel(0) * 0.0722
        }
        val a = luminance(first)
        val b = luminance(second)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }
}
