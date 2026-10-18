package com.wuming.tv.core.catalog

import com.wuming.tv.core.search.TitleNormalizer

/** Recovers stale history IDs without changing ordinary catalog failure behavior. */
object LibraryDetailResolver {
    private val normalizer = TitleNormalizer.identity()

    fun resolve(gateway: CatalogGateway, item: CatalogItem, historyItem: Boolean): CatalogDetail {
        val initial = runCatching { gateway.detail(item.sourceKey, item.id) }
        if (initial.isSuccess || !historyItem || item.name.isBlank()) return initial.getOrThrow()

        val refreshed = gateway.search(item.sourceKey, item.name)
            .firstOrNull { candidate ->
                candidate.kind == CatalogItemKind.MEDIA && normalizer.equivalent(candidate.name, item.name)
            }
            ?: throw initial.exceptionOrNull()!!
        return runCatching { gateway.detail(refreshed.sourceKey, refreshed.id) }
            .getOrElse { throw initial.exceptionOrNull()!! }
    }
}
