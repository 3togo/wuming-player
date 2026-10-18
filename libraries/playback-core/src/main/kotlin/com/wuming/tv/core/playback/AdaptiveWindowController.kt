package com.wuming.tv.core.playback

/**
 * Reversible window probing for range-safe provider profiles. A larger window is retained
 * only when it produces a material gain. A sharp loss immediately halves the window, while
 * a cooldown prevents a globally slow network from causing continuous request churn.
 *
 * Internal to the app in Kotlin. JVM visibility is public because Kotlin has no package-private
 * classes; members intentionally keep unmangled names for the existing Java playback callers.
 */
class AdaptiveWindowController(
    private val minimumWindowBytes: Long,
    private val initialWindowBytes: Long,
    private val maximumWindowBytes: Long,
) {
    enum class Action { NONE, GROW, SHRINK, ROLLBACK }

    private var windowBytes = 0L
    private var baselineBps = 0L
    private var sampleBps = 0L
    private var sampleCount = 0
    private var cooldownWindows = 0
    private var probing = false

    constructor(minimumWindowBytes: Long, maximumWindowBytes: Long) :
        this(minimumWindowBytes, minimumWindowBytes, maximumWindowBytes)

    init {
        require(minimumWindowBytes > 0L && initialWindowBytes >= minimumWindowBytes && maximumWindowBytes >= initialWindowBytes) {
            "Invalid adaptive window bounds"
        }
        reset()
    }

    fun record(bps: Long): Action {
        if (bps <= 0L) return Action.NONE
        if (probing) {
            if (significantLoss(baselineBps, bps)) return rollback()
            sampleBps += bps
            if (++sampleCount < WINDOW_SAMPLES) return Action.NONE
            val averageBps = takeAverage()
            if (significantGain(baselineBps, averageBps)) {
                baselineBps = averageBps
                probing = false
                return Action.NONE
            }
            return rollback()
        }

        if (significantLoss(baselineBps, bps)) {
            clearSamples()
            cooldownWindows = PROBE_COOLDOWN_WINDOWS
            if (windowBytes > minimumWindowBytes) {
                windowBytes = maxOf(minimumWindowBytes, windowBytes / 2L)
                baselineBps = 0L
                return Action.SHRINK
            }
            baselineBps = bps
            return Action.NONE
        }

        if (baselineBps == 0L) baselineBps = bps
        if (cooldownWindows > 0) {
            cooldownWindows--
            baselineBps = maxOf(baselineBps, bps)
            return Action.NONE
        }

        sampleBps += bps
        if (++sampleCount < WINDOW_SAMPLES) return Action.NONE
        baselineBps = maxOf(baselineBps, takeAverage())
        if (windowBytes >= maximumWindowBytes) return Action.NONE
        windowBytes = minOf(maximumWindowBytes, windowBytes * 2L)
        probing = true
        return Action.GROW
    }

    private fun rollback(): Action {
        windowBytes = maxOf(minimumWindowBytes, windowBytes / 2L)
        probing = false
        cooldownWindows = PROBE_COOLDOWN_WINDOWS
        clearSamples()
        return Action.ROLLBACK
    }

    private fun takeAverage(): Long {
        val average = sampleBps / maxOf(1, sampleCount)
        clearSamples()
        return average
    }

    private fun clearSamples() {
        sampleBps = 0L
        sampleCount = 0
    }

    fun reset() {
        windowBytes = initialWindowBytes
        baselineBps = 0L
        cooldownWindows = 0
        probing = false
        clearSamples()
    }

    fun restore(value: Long) {
        reset()
        windowBytes = maxOf(minimumWindowBytes, minOf(maximumWindowBytes, value))
    }

    fun windowBytes(): Long = windowBytes

    fun baselineBps(): Long = baselineBps

    companion object {
        const val WINDOW_SAMPLES = 2
        const val PROBE_COOLDOWN_WINDOWS = 2
        const val GROWTH_PERCENT = 10
        const val LOSS_PERCENT = 30

        @JvmStatic
        fun significantGain(
            baselineBps: Long,
            candidateBps: Long,
        ): Boolean = baselineBps <= 0L || candidateBps >= baselineBps + baselineBps * GROWTH_PERCENT / 100L

        @JvmStatic
        fun significantLoss(
            baselineBps: Long,
            candidateBps: Long,
        ): Boolean = baselineBps > 0L && candidateBps < baselineBps - baselineBps * LOSS_PERCENT / 100L
    }
}
