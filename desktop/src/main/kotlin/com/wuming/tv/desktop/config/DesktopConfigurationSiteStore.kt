package com.wuming.tv.desktop.config

import java.util.prefs.Preferences

/** Remembers the Settings site selection by stable key, independent of label or ordering. */
internal class DesktopConfigurationSiteStore(
    private val preferences: Preferences,
) {
    fun rememberedSiteKey(): String? = preferences.get(SITE_KEY, null)?.takeIf(String::isNotBlank)

    fun rememberSiteKey(siteKey: String) {
        require(siteKey.isNotBlank()) { "Site key is required" }
        preferences.put(SITE_KEY, siteKey)
        preferences.flush()
    }

    private companion object {
        const val SITE_KEY = "configurationSiteKey"
    }
}
