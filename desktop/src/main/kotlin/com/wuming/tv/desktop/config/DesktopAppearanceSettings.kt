package com.wuming.tv.desktop.config

import com.wuming.tv.core.design.Appearance
import com.wuming.tv.core.design.ColorTheme
import java.util.prefs.Preferences

/** Storage port only. Palette selection belongs to design-core. */
class DesktopAppearanceSettings(private val preferences: Preferences = defaultPreferences()) {
    var appearance: Appearance
        get() = Appearance.fromId(preferences.getInt("appearance", Appearance.SYSTEM.id))
        set(value) { preferences.putInt("appearance", value.id) }
    var colorTheme: ColorTheme
        get() = ColorTheme.fromId(preferences.getInt("colorTheme", ColorTheme.CLASSIC.id))
        set(value) { preferences.putInt("colorTheme", value.id) }

    companion object {
        fun defaultPreferences(): Preferences = Preferences.userNodeForPackage(DesktopAppearanceSettings::class.java)
            .node("appearance")
    }
}
