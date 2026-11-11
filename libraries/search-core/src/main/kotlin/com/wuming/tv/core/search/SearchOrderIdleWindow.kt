package com.wuming.tv.core.search

/** Hosts supply monotonic time and schedule UI updates after navigation settles. */
class SearchOrderIdleWindow(private val idleMillis: Long = 2_000) {
    private var lastInput: Long? = null

    fun recordInput(now: Long) { lastInput = now }
    fun reset() { lastInput = null }
    fun canSort(now: Long): Boolean = lastInput?.let { now - it >= idleMillis } ?: true
}
