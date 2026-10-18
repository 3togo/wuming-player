package com.wuming.tv.core.catalog

/** Bounded back navigation; VOD and search each own an independent instance. */
class CatalogNavigationHistory<T>(private val capacity: Int = 24) {
    init { require(capacity > 0) }
    private val entries = ArrayDeque<T>()
    val size: Int get() = entries.size
    fun isNotEmpty() = entries.isNotEmpty()
    fun clear() = entries.clear()
    fun pop(): T? = entries.removeLastOrNull()
    fun push(value: T) {
        entries.addLast(value)
        while (entries.size > capacity) entries.removeFirst()
    }
}
