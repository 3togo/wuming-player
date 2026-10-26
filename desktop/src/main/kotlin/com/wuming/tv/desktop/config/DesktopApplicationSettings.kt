package com.wuming.tv.desktop.config

import java.util.prefs.Preferences

/** Linux application startup preferences, independent of playback and appearance. */
class DesktopApplicationSettings(
    private val preferences: Preferences = Preferences.userNodeForPackage(DesktopApplicationSettings::class.java).node("application"),
) {
    var preventMultiplePlayerWindows: Boolean
        get() = preferences.getBoolean("preventMultiplePlayerWindows", true)
        set(value) { preferences.putBoolean("preventMultiplePlayerWindows", value); preferences.flush() }

    var preventMultipleInstances: Boolean
        get() = preferences.getBoolean("preventMultipleInstances", true)
        set(value) { preferences.putBoolean("preventMultipleInstances", value); preferences.flush() }
}
