package com.wuming.tv.core.live

/**
 * One measured attempt at a live line. Produced by a platform prober; the policy
 * never measures anything itself.
 */
data class LiveLineMeasurement(val reachable: Boolean, val latencyMillis: Long = 0L)

/**
 * Chooses which of a channel's duplicate lines to play.
 *
 * Live playlists routinely list the same channel on several URLs, most of them dead,
 * and the app currently starts on whichever line the playlist happened to write first.
 * A line is only replaced when there is evidence against it, so a line the user picked
 * by hand or from a remembered position is never second-guessed without a failed probe.
 */
object LiveLineSelectionPolicy {

    /**
     * The index to play. Keeps [requested] unless that line is known unreachable and
     * another line is known to answer, in which case the quickest answering line wins.
     */
    @JvmStatic
    fun select(lines: List<String>, requested: Int, measurements: Map<String, LiveLineMeasurement>): Int {
        if (lines.isEmpty()) return 0
        val index = requested.coerceIn(0, lines.lastIndex)
        val current = lines[index]
        if (measurements[current]?.reachable ?: true) return index
        val replacement = fastest(lines.filterNot { it == current }, measurements) ?: return index
        return lines.indexOf(replacement)
    }

    /** The quickest known-reachable line among [lines], or `null` while none has answered. */
    @JvmStatic
    fun fastest(lines: List<String>, measurements: Map<String, LiveLineMeasurement>): String? =
        lines.mapNotNull { url -> measurements[url]?.takeIf { it.reachable }?.let { url to it.latencyMillis } }
            .minByOrNull { it.second }
            ?.first

    /**
     * Whether a line should be measured. Lines already answered or probed are left alone
     * so channel zapping does not repeat work, and a known-dead line is re-probed only
     * after [REPROBE_AFTER_MILLIS] so a recovering source is not stuck forever.
     */
    @JvmStatic
    fun shouldProbe(
        measurement: LiveLineMeasurement?,
        nowMillis: Long,
        measuredAtMillis: Long,
    ): Boolean = when {
        measurement == null -> true
        measurement.reachable -> false
        else -> nowMillis - measuredAtMillis >= REPROBE_AFTER_MILLIS
    }

    /** Lines marked unreachable are re-checked after this long. */
    const val REPROBE_AFTER_MILLIS = 30 * 60 * 1000L
}
