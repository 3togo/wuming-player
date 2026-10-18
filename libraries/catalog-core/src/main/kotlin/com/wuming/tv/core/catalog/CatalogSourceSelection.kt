package com.wuming.tv.core.catalog

/**
 * Resolves a UI selection whose first entry is a synthetic "Show All" choice.
 *
 * [sources] contains real providers only. Consequently selection index 1 maps to
 * the first configured provider instead of silently replacing it.
 */
object CatalogSourceSelection {

    @JvmStatic
    fun targets(sources: List<CatalogSource>, selectedIndex: Int): List<CatalogSource> = when {
        selectedIndex == 0 -> sources
        selectedIndex in 1..sources.size -> listOf(sources[selectedIndex - 1])
        else -> emptyList()
    }
}
