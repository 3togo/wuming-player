package com.wuming.tv.core.catalog

/** Configuration owns site identity/order; runtime availability must not silently remove rows. */
object CatalogSourceRoster {
    // Settings lists configured sites, including inactive ones. Deriving VOD rows from
    // executors instead made the two screens disagree and changed their ordering.
    fun displayed(configured: List<CatalogSource>, active: List<CatalogSource>): List<CatalogSource> =
        (if (configured.isEmpty()) active else configured).distinctBy(CatalogSource::key)

    fun runnable(configured: List<CatalogSource>, active: List<CatalogSource>): List<CatalogSource> {
        val keys = active.map(CatalogSource::key).toSet()
        return displayed(configured, active).filter { it.key in keys }
    }

    fun targets(available: List<CatalogSource>, selectedKey: String?): List<CatalogSource> =
        if (selectedKey == null) available else available.filter { it.key == selectedKey }
}
