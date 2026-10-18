package com.wuming.android.tv.utils

object DepotPrune {
    const val PRUNE_AFTER_FAILS = com.wuming.tv.core.source.DepotPrunePolicy.PRUNE_AFTER_FAILS
    @JvmStatic fun nextFailCount(previous: Int, ok: Boolean): Int =
        com.wuming.tv.core.source.DepotPrunePolicy.nextFailCount(previous, ok)
    @JvmStatic fun keep(failCount: Int): Boolean = com.wuming.tv.core.source.DepotPrunePolicy.keep(failCount)
}
