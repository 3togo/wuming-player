package com.wuming.tv.desktop.catalog

import com.wuming.tv.core.catalog.*
import com.wuming.tv.desktop.cache.MemoryResultCache
import java.time.Clock
import java.time.Duration

/** Each wrapper belongs to exactly one configuration/runtime instance; playback is never cached. */
internal class CachingCatalogSourceExecutor(
    private val delegate: CatalogSourceExecutor,
    private val accountGeneration: () -> Long = { com.wuming.tv.desktop.cache.DesktopCacheGeneration.value.get() },
    clock: Clock = Clock.systemUTC(),
) : CatalogSourceExecutor, AutoCloseable {
    override val source get() = delegate.source
    private val pages = MemoryResultCache<List<Any>, Any>(128, Duration.ofMinutes(5), clock)
    private val searches = MemoryResultCache<String, List<CatalogItem>>(100, Duration.ofMinutes(2), clock)
    private val details = MemoryResultCache<String, CatalogDetail>(256, Duration.ofMinutes(10), clock)
    private var account = accountGeneration()

    @Synchronized private fun checkAccount() {
        val next = accountGeneration()
        if (next != account) { clear(); account = next }
    }

    fun clear() { pages.clear(); searches.clear(); details.clear() }
    override fun home(): List<CatalogItem> {
        checkAccount()
        @Suppress("UNCHECKED_CAST")
        return pages.get(listOf("home")) { delegate.home() } as List<CatalogItem>
    }
    override fun browseHome(): CatalogHome {
        checkAccount()
        return pages.get(listOf("browseHome")) { delegate.browseHome() } as CatalogHome
    }
    override fun category(typeId: String, page: Int, filters: Map<String, String>): CatalogPage {
        checkAccount()
        val snapshot = filters.toSortedMap()
        return pages.get(listOf("category", typeId, page, snapshot)) { delegate.category(typeId, page, snapshot) } as CatalogPage
    }
    override fun search(query: String): List<CatalogItem> {
        checkAccount()
        return searches.get(query) { delegate.search(query) }
    }
    override fun detail(id: String): CatalogDetail {
        checkAccount()
        return details.get(id) { delegate.detail(id) }
    }
    override fun play(flag: String, id: String): CatalogPlayback = delegate.play(flag, id)
    override fun cancel() { clear(); delegate.cancel() }
    override fun close() { clear(); (delegate as? AutoCloseable)?.close() }
}
