package com.wuming.tv.core.base

/** Zero-based bounded page view. An out-of-range page is empty rather than clamped. */
object ListPages {
    @JvmStatic
    fun <T> slice(items: List<T>, page: Int, pageSize: Int): List<T> {
        require(page >= 0) { "Page must be non-negative" }
        require(pageSize > 0) { "Page size must be positive" }
        val start = page.toLong() * pageSize
        if (start >= items.size) return emptyList()
        return items.subList(start.toInt(), minOf(start + pageSize, items.size.toLong()).toInt())
    }
}
