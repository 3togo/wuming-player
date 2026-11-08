package com.wuming.tv.desktop.catalog

import com.wuming.tv.core.catalog.CatalogSourceExecutor
import com.wuming.tv.desktop.cache.DesktopCacheGeneration
import java.time.Clock

/** Supplies the desktop account-change signal to the shared metadata cache. */
internal class CachingCatalogSourceExecutor private constructor(
    private val cached: com.wuming.tv.core.catalog.CachingCatalogSourceExecutor,
) : CatalogSourceExecutor by cached, AutoCloseable {
    constructor(
        delegate: CatalogSourceExecutor,
        accountGeneration: () -> Long = { DesktopCacheGeneration.value.get() },
        clock: Clock = Clock.systemUTC(),
    ) : this(com.wuming.tv.core.catalog.CachingCatalogSourceExecutor(delegate, accountGeneration, clock))

    fun clear() = cached.clear()
    override fun close() = cached.close()
}
