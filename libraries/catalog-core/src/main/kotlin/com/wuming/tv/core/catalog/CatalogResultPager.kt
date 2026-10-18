package com.wuming.tv.core.catalog

/** Adapts fixed provider pages to user-sized display pages, retaining overflow between requests. */
class CatalogResultPager {
    private val loaded = mutableListOf<CatalogItem>()
    private val identities = mutableSetOf<Pair<String, String>>()
    private var nextSourcePage = 1
    private var exhausted = false

    @Synchronized
    fun load(page: Int, size: Int, fetch: (Int) -> CatalogPage): CatalogPage {
        val displayPage = page.coerceAtLeast(1)
        val pageSize = CatalogPagination.size(size)
        val needed = displayPage.toLong() * pageSize
        while (!exhausted && loaded.size.toLong() < needed) {
            val response = fetch(nextSourcePage)
            val added = response.items.filter { identities.add(it.sourceKey to it.id) }
            loaded.addAll(added)
            nextSourcePage++
            // Some sources ignore page numbers or report an unknown end forever.
            exhausted = response.items.isEmpty() || added.isEmpty() || !response.hasNext
        }
        val count = if (exhausted) CatalogPagination.pageCount(loaded.size, pageSize) else 0
        val actualPage = if (exhausted) displayPage.coerceAtMost(count) else displayPage
        val start = (actualPage - 1) * pageSize
        return CatalogPage(
            items = loaded.subList(start, minOf(start + pageSize, loaded.size)).toList(),
            page = actualPage,
            pageCount = count,
            total = if (exhausted) loaded.size else 0,
        )
    }

    /**
     * Loads the requested display page but returns every item from page one through that page.
     *
     * This is the shared policy used by scrolling clients: provider page sizes stay hidden, the
     * visible list can grow without replacing earlier cards, and explicit page navigation can
     * still address the same display-page boundaries as [load].
     */
    @Synchronized
    fun loadThrough(page: Int, size: Int, fetch: (Int) -> CatalogPage): CatalogPage {
        val result = load(page, size, fetch)
        val end = minOf(result.page * CatalogPagination.size(size), loaded.size)
        return result.copy(items = loaded.subList(0, end).toList())
    }
}
