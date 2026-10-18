package com.wuming.tv.core.playback

/**
 * Persisted known-good parallel range setting plus its probe state.
 *
 * The encoding is the on-disk contract of the platform profile store: five fields for records
 * written before probing existed, nine after the probe axes were added, and eleven once probe
 * cooldowns and write revisions became part of the profile. Decoding rejects anything out of range
 * instead of repairing it, so a corrupt record falls back to "no learning".
 */
data class RangeTuningProfile(
    val ranges: Int,
    val rangeBytes: Long,
    val observedBps: Long,
    val confidence: Int,
    val updatedAt: Long,
    val nextAxis: Int,
    val countDirection: Int,
    val sizeDirection: Int,
    val probeCooldown: Int,
    val probeNotBefore: Long,
    val revision: Long
) {
    constructor(ranges: Int, rangeBytes: Long, observedBps: Long,
                confidence: Int, updatedAt: Long) : this(
        ranges, rangeBytes, observedBps, confidence, updatedAt,
        RangeProbeOptimizer.AXIS_COUNT, 1, 1, 0, 0L, 0L)

    constructor(ranges: Int, rangeBytes: Long, observedBps: Long,
                confidence: Int, updatedAt: Long, nextAxis: Int,
                countDirection: Int, sizeDirection: Int, probeCooldown: Int) : this(
        ranges, rangeBytes, observedBps, confidence, updatedAt, nextAxis,
        countDirection, sizeDirection, probeCooldown, 0L, 0L)

    @JvmName("ranges")
    fun rangesAccessor(): Int = ranges
    @JvmName("rangeBytes")
    fun rangeBytesAccessor(): Long = rangeBytes
    @JvmName("observedBps")
    fun observedBpsAccessor(): Long = observedBps
    @JvmName("confidence")
    fun confidenceAccessor(): Int = confidence
    @JvmName("updatedAt")
    fun updatedAtAccessor(): Long = updatedAt
    @JvmName("nextAxis")
    fun nextAxisAccessor(): Int = nextAxis
    @JvmName("countDirection")
    fun countDirectionAccessor(): Int = countDirection
    @JvmName("sizeDirection")
    fun sizeDirectionAccessor(): Int = sizeDirection
    @JvmName("probeCooldown")
    fun probeCooldownAccessor(): Int = probeCooldown
    @JvmName("probeNotBefore")
    fun probeNotBeforeAccessor(): Long = probeNotBefore
    @JvmName("revision")
    fun revisionAccessor(): Long = revision

    fun encode(): String {
        return "$ranges,$rangeBytes,$observedBps," +
            "$confidence,$updatedAt,$nextAxis," +
            "$countDirection,$sizeDirection,$probeCooldown," +
            "$probeNotBefore,$revision"
    }

    companion object {
        @JvmStatic
        fun decode(value: String?): RangeTuningProfile? {
            if (value == null || value.isBlank()) return null
            val fields = value.split(",")
            if (fields.size != 5 && fields.size != 9 && fields.size != 11) return null
            try {
                val result = RangeTuningProfile(
                    fields[0].toInt(),
                    fields[1].toLong(),
                    fields[2].toLong(),
                    fields[3].toInt(),
                    fields[4].toLong(),
                    if (fields.size >= 9) fields[5].toInt() else RangeProbeOptimizer.AXIS_COUNT,
                    if (fields.size >= 9) fields[6].toInt() else 1,
                    if (fields.size >= 9) fields[7].toInt() else 1,
                    if (fields.size >= 9) fields[8].toInt() else 0,
                    if (fields.size == 11) fields[9].toLong() else 0L,
                    if (fields.size == 11) fields[10].toLong() else 0L)
                return if (result.ranges > 0 && result.rangeBytes > 0L
                    && result.observedBps >= 0L && result.confidence > 0
                    && result.updatedAt > 0L && result.probeCooldown >= 0
                    && result.probeNotBefore >= 0L && result.revision >= 0L)
                    result else null
            } catch (_: NumberFormatException) {
                return null
            }
        }
    }
}
