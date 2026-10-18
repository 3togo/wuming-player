package com.wuming.tv.desktop.ui

import org.junit.Assert.*
import org.junit.Test

class LinuxSystemAppearanceTest {
    @Test fun `portal preference takes priority over GTK settings`() {
        for ((value, dark) in listOf(1 to true, 2 to false)) {
            val detector = LinuxSystemAppearance({ args ->
                assertEquals("gdbus", args.first())
                "(<<uint32 $value>>,)"
            }, { error("Portal should win") })
            assertEquals(dark, detector.isDark())
        }
    }

    @Test fun `unavailable or neutral portal falls back to GNOME and GTK`() {
        for (portal in listOf(null, "(<<uint32 0>>,)")) {
            for (scheme in listOf("prefer-dark", "prefer-light")) {
                val detector = LinuxSystemAppearance({ args ->
                    if (args.first() == "gdbus") portal else "'$scheme'"
                }, { null })
                assertEquals(scheme == "prefer-dark", detector.isDark())
            }
        }
        assertTrue(LinuxSystemAppearance({ null }, { "Adwaita:dark" }).isDark())
        assertFalse(LinuxSystemAppearance({ null }, { null }).isDark())
        assertNull(LinuxSystemAppearance.parsePortal("error"))
    }
}
