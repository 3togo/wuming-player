package com.wuming.tv.desktop.config

import java.util.prefs.Preferences

/** Persists the semantic VOD selection; the synthetic Show All row is not a provider. */
internal class DesktopCatalogSelectionStore(
    private val preferences: Preferences,
) {
    fun rememberedSelection(): String? = preferences.get(SELECTION, null)

    fun rememberShowAll() = remember(SHOW_ALL)

    fun rememberSource(sourceKey: String) {
        require(sourceKey.isNotBlank()) { "Source key is required" }
        remember(SOURCE_PREFIX + sourceKey)
    }

    fun sourceKey(selection: String?): String? = selection
        ?.takeIf { it.startsWith(SOURCE_PREFIX) }
        ?.removePrefix(SOURCE_PREFIX)
        ?.takeIf(String::isNotBlank)

    fun isShowAll(selection: String?): Boolean = selection == SHOW_ALL

    private fun remember(value: String) {
        preferences.put(SELECTION, value)
        preferences.flush()
    }

    private companion object {
        const val SELECTION = "catalogSelection"
        const val SHOW_ALL = "show-all"
        const val SOURCE_PREFIX = "source:"
    }
}
