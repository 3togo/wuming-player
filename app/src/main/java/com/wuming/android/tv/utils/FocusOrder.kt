package com.wuming.android.tv.utils

import java.util.function.IntPredicate

/** Resolves D-pad destinations from a logical order whose entries may be unavailable. */
object FocusOrder {
    @JvmStatic
    fun before(order: List<Int>, current: Int, available: IntPredicate): Int {
        val index = order.indexOf(current)
        if (index < 0) return 0
        for (i in index - 1 downTo 0) {
            val candidate = order[i]
            if (available.test(candidate)) return candidate
        }
        return 0
    }

    @JvmStatic
    fun after(order: List<Int>, current: Int, available: IntPredicate): Int {
        val index = order.indexOf(current)
        if (index < 0) return 0
        for (i in index + 1 until order.size) {
            val candidate = order[i]
            if (available.test(candidate)) return candidate
        }
        return 0
    }
}
