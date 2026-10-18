package com.wuming.tv.desktop.config

import java.util.prefs.Preferences

/** Persists only configurations that completed decoding and validation successfully. */
internal class DesktopConfigurationUrlStore(
    private val preferences: Preferences,
) {
    fun lastWorkingOr(defaultLocation: String): String =
        preferences.get(CONFIGURATION_URL, defaultLocation).trim().ifBlank { defaultLocation }

    fun rememberWorking(location: String) {
        preferences.put(CONFIGURATION_URL, location)
        preferences.flush()
    }

    private companion object {
        const val CONFIGURATION_URL = "configurationUrl"
    }
}
