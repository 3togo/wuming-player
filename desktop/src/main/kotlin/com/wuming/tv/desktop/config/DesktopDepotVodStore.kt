package com.wuming.tv.desktop.config

import java.util.prefs.Preferences

/** Persists the Settings depot VOD source selection so it survives restarts. */
internal class DesktopDepotVodStore(
    private val preferences: Preferences,
) {
    fun rememberedLocation(): String? = preferences.get(DEPOT_VOD_LOCATION, null)?.takeIf(String::isNotBlank)

    fun rememberLocation(location: String) {
        require(location.isNotBlank()) { "Depot VOD location is required" }
        preferences.put(DEPOT_VOD_LOCATION, location)
        preferences.flush()
    }

    private companion object {
        const val DEPOT_VOD_LOCATION = "depotVodLocation"
    }
}