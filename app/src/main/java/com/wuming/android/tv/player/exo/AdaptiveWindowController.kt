package com.wuming.android.tv.player.exo

/** Java caller compatibility; all window decisions live in playback-core. */
internal class AdaptiveWindowController(minimum: Long, initial: Long, maximum: Long) {
    enum class Action { NONE, GROW, SHRINK, ROLLBACK }
    private val shared = com.wuming.tv.core.playback.AdaptiveWindowController(minimum, initial, maximum)
    constructor(minimum: Long, maximum: Long) : this(minimum, minimum, maximum)
    fun record(bps: Long): Action = Action.valueOf(shared.record(bps).name)
    fun reset() = shared.reset()
    fun restore(value: Long) = shared.restore(value)
    fun windowBytes(): Long = shared.windowBytes()
    fun baselineBps(): Long = shared.baselineBps()
    companion object {
        const val WINDOW_SAMPLES = com.wuming.tv.core.playback.AdaptiveWindowController.WINDOW_SAMPLES
        const val PROBE_COOLDOWN_WINDOWS = com.wuming.tv.core.playback.AdaptiveWindowController.PROBE_COOLDOWN_WINDOWS
        const val GROWTH_PERCENT = com.wuming.tv.core.playback.AdaptiveWindowController.GROWTH_PERCENT
        const val LOSS_PERCENT = com.wuming.tv.core.playback.AdaptiveWindowController.LOSS_PERCENT
        @JvmStatic fun significantGain(baselineBps: Long, candidateBps: Long): Boolean =
            com.wuming.tv.core.playback.AdaptiveWindowController.significantGain(baselineBps, candidateBps)
        @JvmStatic fun significantLoss(baselineBps: Long, candidateBps: Long): Boolean =
            com.wuming.tv.core.playback.AdaptiveWindowController.significantLoss(baselineBps, candidateBps)
    }
}
