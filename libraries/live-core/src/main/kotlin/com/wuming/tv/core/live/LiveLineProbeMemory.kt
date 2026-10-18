package com.wuming.tv.core.live

import java.util.LinkedHashMap

/**
 * Remembers how each live line answered so a dead line is not re-attempted until its reprobe window
 * passes. Shared by the Android and desktop probers (which differ only in their HTTP stack); the
 * access-order [LinkedHashMap] evicts the least-recently-touched entry once it grows past
 * [MAX_REMEMBERED].
 */
class LiveLineProbeMemory(
    private val maxRemembered: Int = MAX_REMEMBERED,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** One remembered attempt at a live line. */
    data class Sample(val measurement: LiveLineMeasurement, val measuredAt: Long)

    private val remembered = object : LinkedHashMap<String, Sample>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Sample>?): Boolean =
            size > maxRemembered
    }

    /** Stores the outcome of probing [url]. */
    fun remember(url: String, measurement: LiveLineMeasurement) = synchronized(remembered) {
        remembered[url] = Sample(measurement, clock())
    }

    /** The remembered sample for [url], or null when it has never been probed. */
    fun sample(url: String): Sample? = synchronized(remembered) { remembered[url] }

    /** Every remembered measurement, regardless of freshness, for inspecting stored outcomes. */
    fun allMeasurements(): Map<String, LiveLineMeasurement> = synchronized(remembered) {
        remembered.mapValues { it.value.measurement }
    }

    /**
     * Lines whose answer still stands; an entry that [LiveLineSelectionPolicy.shouldProbe] would flag
     * is dropped so it reads as unknown (the desktop port uses this before ordering candidates).
     */
    fun measuredLines(): Map<String, LiveLineMeasurement> = synchronized(remembered) {
        val now = clock()
        remembered.entries
            .filterNot { (_, sample) -> LiveLineSelectionPolicy.shouldProbe(sample.measurement, now, sample.measuredAt) }
            .associate { (url, sample) -> url to sample.measurement }
    }

    /** Whether [url] should be probed again now (true when unknown or past its reprobe window). */
    fun shouldReprobe(url: String): Boolean = synchronized(remembered) {
        val entry = remembered[url]
        LiveLineSelectionPolicy.shouldProbe(entry?.measurement, clock(), entry?.measuredAt ?: 0L)
    }

    companion object {
        /** Zapping revisits the same channels; enough room for a session's worth of lines. */
        const val MAX_REMEMBERED = 200
    }
}
