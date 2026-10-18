package com.wuming.tv.core.catalog

/** Local presentation pages within a provider response; provider cursors stay independent. */
object CatalogPagination {
    const val DEFAULT_SIZE = 24
    const val MIN_SIZE = 6
    const val MAX_SIZE = 120
    fun size(value: Int): Int = value.coerceIn(MIN_SIZE, MAX_SIZE)
    fun pageCount(total: Int, pageSize: Int): Int = if (total <= 0) 1 else (total - 1) / size(pageSize) + 1
    fun page(total: Int, pageSize: Int, requested: Int): Int = requested.coerceIn(0, pageCount(total, pageSize) - 1)
    fun <T> slice(items: List<T>, pageSize: Int, requested: Int): List<T> {
        val start = page(items.size, pageSize, requested) * size(pageSize)
        return items.subList(start, (start + size(pageSize)).coerceAtMost(items.size))
    }
}
