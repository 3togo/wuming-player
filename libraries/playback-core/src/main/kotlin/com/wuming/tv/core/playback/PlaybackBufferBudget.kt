package com.wuming.tv.core.playback

/** Byte budgets for compressed media; decoder surfaces and application data need separate RAM. */
object PlaybackBufferBudget {
    const val MIB = 1024 * 1024
    const val MIN_BYTES = 4 * MIB
    const val MAX_BYTES = 96 * MIB
    private const val LOW_RAM_MAX_BYTES = 24 * MIB

    @JvmRecord data class Memory(
        val heapLimit: Long,
        val heapUsed: Long,
        val availableRam: Long,
        val lowMemoryThreshold: Long,
        val lowRamDevice: Boolean,
        val lowMemory: Boolean,
    ) {
        fun initialBytes(): Int {
            val limit = maxOf(0, heapLimit)
            val headroom = maxOf(0, limit - maxOf(0, heapUsed))
            var budget = minOf(limit / 4, maxOf(0, headroom - reserveBytes()) / 2)
            // Negative availableRam means no platform measurement is available.
            if (availableRam >= 0) budget = minOf(budget, maxOf(0, availableRam - maxOf(0, lowMemoryThreshold)) / 4)
            budget = minOf(budget, (if (lowRamDevice) LOW_RAM_MAX_BYTES else MAX_BYTES).toLong())
            return if (lowMemory) MIN_BYTES else maxOf(MIN_BYTES.toLong(), budget).toInt()
        }

        fun constrainBytes(currentBytes: Int): Int {
            val headroom = maxOf(0, heapLimit - maxOf(0, heapUsed))
            val pressure = lowMemory || headroom < reserveBytes() || (availableRam >= 0 && availableRam <= lowMemoryThreshold)
            // Never grow the session budget after GC; under pressure, drain queued media.
            return if (pressure) maxOf(MIN_BYTES, currentBytes / 2) else currentBytes
        }

        private fun reserveBytes(): Long = maxOf(16L * MIB, minOf(48L * MIB, heapLimit / 8))
    }
}
