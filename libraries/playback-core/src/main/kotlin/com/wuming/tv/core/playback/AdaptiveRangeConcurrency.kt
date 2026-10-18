package com.wuming.tv.core.playback

/** Session-local, aggregate-rate control. Incomplete/cancelled batches are never evidence. */
class AdaptiveRangeConcurrency {
    var active = 1
        private set
    private var best = 1
    private var ceiling = MAX_CONNECTIONS
    private var samples = 0
    private var bytes = 0L
    private var millis = 0L
    private var bestRate = 0L
    private var notBefore = 0L

    fun connections(): Int = active
    fun notBeforeMs(): Long = notBefore

    fun complete(batchBytes: Long, elapsedMs: Long): String {
        if (batchBytes <= 0 || elapsedMs <= 0) return "none"
        bytes += batchBytes
        millis += elapsedMs
        if (++samples < 2) return "sample"
        val rate = bytes * 1000 / maxOf(1, millis)
        resetSamples()
        if (active > best && rate < bestRate + bestRate / 10) {
            ceiling = best
            active = best
            return "rollback"
        }
        if (active == best && bestRate > 0 && rate < bestRate - bestRate / 10) {
            ceiling = lower(active)
            active = ceiling
            best = ceiling
            bestRate = 0
            return "decrease"
        }
        best = active
        bestRate = rate
        val next = if (active == 1) 2 else active + 2
        if (next <= ceiling) {
            active = next
            return "increase"
        }
        return "hold"
    }

    fun rejected(nowMs: Long, retryAfterMs: Long) {
        ceiling = maxOf(1, if (active > best) best else lower(active))
        active = ceiling
        best = ceiling
        bestRate = 0
        notBefore = maxOf(notBefore, nowMs + maxOf(5000, retryAfterMs))
        resetSamples()
    }

    private fun lower(n: Int): Int = if (n <= 2) 1 else n - 2
    private fun resetSamples() { bytes = 0; millis = 0; samples = 0 }

    companion object {
        const val MAX_CONNECTIONS = 16
    }
}
