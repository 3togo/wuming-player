package com.wuming.android.tv.player.exo

/** Byte budgets for compressed media; decoder surfaces and application data need separate RAM. */
internal object PlaybackBufferBudget {
    const val MIB = com.wuming.tv.core.playback.PlaybackBufferBudget.MIB
    const val MIN_BYTES = com.wuming.tv.core.playback.PlaybackBufferBudget.MIN_BYTES
    const val MAX_BYTES = com.wuming.tv.core.playback.PlaybackBufferBudget.MAX_BYTES

    @JvmRecord data class Memory(
        val heapLimit: Long,
        val heapUsed: Long,
        val availableRam: Long,
        val lowMemoryThreshold: Long,
        val lowRamDevice: Boolean,
        val lowMemory: Boolean,
    ) {
        private fun shared() = com.wuming.tv.core.playback.PlaybackBufferBudget.Memory(
            heapLimit, heapUsed, availableRam, lowMemoryThreshold, lowRamDevice, lowMemory)
        fun initialBytes(): Int = shared().initialBytes()
        fun constrainBytes(currentBytes: Int): Int = shared().constrainBytes(currentBytes)
    }
}
