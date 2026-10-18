package com.wuming.tv.core.playback

import java.lang.ArithmeticException
import kotlin.math.max
import kotlin.math.min

/**
 * Bounded one-variable-at-a-time optimizer for parallel byte ranges.
 *
 * The persisted profile is always the known-good setting. A candidate exists only in memory
 * until two complete network batches prove a material gain, so process death or cancellation
 * automatically rolls back to the previous best.
 */
class RangeProbeOptimizer(
    defaultSetting: Setting,
    private val bounds: Bounds,
    persisted: RangeTuningProfile?
) {
    enum class Action { NONE, PROBE, COMMIT, ROLLBACK }

    class Setting(val ranges: Int, val rangeBytes: Long) {
        init {
            if (ranges <= 0 || rangeBytes <= 0L) {
                throw IllegalArgumentException("Invalid range setting")
            }
        }

        @JvmName("ranges")
        fun rangesAccessor(): Int = ranges
        @JvmName("rangeBytes")
        fun rangeBytesAccessor(): Long = rangeBytes

        fun windowBytes(): Long {
            return try {
                Math.multiplyExact(ranges.toLong(), rangeBytes)
            } catch (_: ArithmeticException) {
                Long.MAX_VALUE
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Setting) return false
            return ranges == other.ranges && rangeBytes == other.rangeBytes
        }

        override fun hashCode(): Int = 31 * ranges + rangeBytes.hashCode()
    }

    class Bounds(
        val minimumRanges: Int,
        val maximumRanges: Int,
        val minimumRangeBytes: Long,
        val maximumRangeBytes: Long,
        val minimumWindowBytes: Long,
        val maximumWindowBytes: Long
    ) {
        init {
            if (minimumRanges <= 0 || maximumRanges < minimumRanges
                || minimumRangeBytes <= 0L || maximumRangeBytes < minimumRangeBytes
                || minimumWindowBytes <= 0L || maximumWindowBytes < minimumWindowBytes) {
                throw IllegalArgumentException("Invalid range optimizer bounds")
            }
        }

        @JvmName("minimumRanges")
        fun minimumRangesAccessor(): Int = minimumRanges
        @JvmName("maximumRanges")
        fun maximumRangesAccessor(): Int = maximumRanges
        @JvmName("minimumRangeBytes")
        fun minimumRangeBytesAccessor(): Long = minimumRangeBytes
        @JvmName("maximumRangeBytes")
        fun maximumRangeBytesAccessor(): Long = maximumRangeBytes
        @JvmName("minimumWindowBytes")
        fun minimumWindowBytesAccessor(): Long = minimumWindowBytes
        @JvmName("maximumWindowBytes")
        fun maximumWindowBytesAccessor(): Long = maximumWindowBytes

        fun contains(setting: Setting?): Boolean {
            if (setting == null || setting.ranges < minimumRanges
                || setting.ranges > maximumRanges
                || setting.rangeBytes < minimumRangeBytes
                || setting.rangeBytes > maximumRangeBytes) return false
            val window = setting.windowBytes()
            return window >= minimumWindowBytes && window <= maximumWindowBytes
        }
    }

    class Decision(val action: Action, val setting: Setting, val profile: RangeTuningProfile?) {
        @JvmName("action")
        fun actionAccessor(): Action = action
        @JvmName("setting")
        fun settingAccessor(): Setting = setting
        @JvmName("profile")
        fun profileAccessor(): RangeTuningProfile? = profile

        companion object {
            @JvmStatic
            fun none(setting: Setting): Decision = Decision(Action.NONE, setting, null)
        }
    }

    class Observation(
        bytesPerSecond: Long,
        firstByteNanos: Long,
        playbackMarginPermille: Int,
        rebufferId: Long
    ) {
        val bytesPerSecond: Long = max(0L, bytesPerSecond)
        val firstByteNanos: Long = max(0L, firstByteNanos)
        val playbackMarginPermille: Int = max(0, playbackMarginPermille)
        val rebufferId: Long = max(0L, rebufferId)

        @JvmName("bytesPerSecond")
        fun bytesPerSecondAccessor(): Long = bytesPerSecond
        @JvmName("firstByteNanos")
        fun firstByteNanosAccessor(): Long = firstByteNanos
        @JvmName("playbackMarginPermille")
        fun playbackMarginPermilleAccessor(): Int = playbackMarginPermille
        @JvmName("rebufferId")
        fun rebufferIdAccessor(): Long = rebufferId
    }

    private class Candidate(val axis: Int, val setting: Setting)

    private var best: Setting
    private var active: Setting
    private var candidate: Setting? = null
    private var bestBps: Long
    private var confidence: Int
    private var cooldown: Int
    private var probeNotBefore: Long
    private var nextAxis: Int
    private var countDirection: Int
    private var sizeDirection: Int
    private var candidateAxis: Int = 0
    private var baselineTotal: Long = 0L
    private var baselineSamples: Int = 0
    private var baselineLatencyTotal: Long = 0L
    private var baselineLatencySamples: Int = 0
    private var baselineMarginTotal: Long = 0L
    private var baselineMarginSamples: Int = 0
    private var baselineRebufferId: Long = 0L
    private var candidateTotal: Long = 0L
    private var candidateSamples: Int = 0
    private var candidateLatencyTotal: Long = 0L
    private var candidateLatencySamples: Int = 0
    private var candidateMarginTotal: Long = 0L
    private var candidateMarginSamples: Int = 0
    private var candidateRebufferId: Long = 0L
    private var probing: Boolean = false
    private var probeFinished: Boolean = false

    init {
        if (!bounds.contains(defaultSetting)) {
            throw IllegalArgumentException("Default setting is outside optimizer bounds")
        }
        val learned = if (persisted == null) null
            else Setting(persisted.ranges, persisted.rangeBytes)
        val learnedValid = persisted != null && bounds.contains(learned)
        best = if (learnedValid) learned!! else defaultSetting
        active = best
        bestBps = if (learnedValid) max(0L, persisted.observedBps) else 0L
        confidence = if (learnedValid) clampConfidence(persisted.confidence) else 0
        cooldown = if (learnedValid) max(0, persisted.probeCooldown) else 0
        probeNotBefore = if (learnedValid) max(0L, persisted.probeNotBefore) else 0L
        nextAxis = if (learnedValid) normalizeAxis(persisted.nextAxis) else AXIS_COUNT
        countDirection = if (learnedValid) normalizeDirection(persisted.countDirection) else 1
        sizeDirection = if (learnedValid) normalizeDirection(persisted.sizeDirection) else 1
    }

    fun setting(): Setting = active
    fun bestSetting(): Setting = best
    fun probing(): Boolean = probing

    fun recordBatch(bytesPerSecond: Long, now: Long): Decision {
        return recordBatch(Observation(bytesPerSecond, 0L, 0, 0L), now)
    }

    fun recordBatch(observation: Observation?, now: Long): Decision {
        if (observation == null || observation.bytesPerSecond <= 0L) {
            return Decision.none(active)
        }
        if (probing) return recordCandidate(observation, now)
        baselineTotal = saturatedAdd(baselineTotal, observation.bytesPerSecond)
        baselineSamples++
        if (observation.firstByteNanos > 0L) {
            baselineLatencyTotal = saturatedAdd(baselineLatencyTotal, observation.firstByteNanos)
            baselineLatencySamples++
        }
        if (observation.playbackMarginPermille > 0) {
            baselineMarginTotal = saturatedAdd(baselineMarginTotal, observation.playbackMarginPermille.toLong())
            baselineMarginSamples++
        }
        baselineRebufferId = max(baselineRebufferId, observation.rebufferId)
        if (cooldown > 0 && now >= probeNotBefore) {
            cooldown = 0
            probeNotBefore = 0L
        }
        if (baselineSamples < REQUIRED_SAMPLES || probeFinished || cooldown > 0) {
            return Decision.none(active)
        }
        val next = chooseCandidate()
        if (next == null) {
            cooldown = REJECTED_PROBE_COOLDOWN
            probeNotBefore = cooldownDeadline(now, REJECTED_PROBE_COOLDOWN_MILLIS)
            probeFinished = true
            return Decision(Action.NONE, active, snapshot(averageBaseline(), now))
        }
        bestBps = smooth(bestBps, averageBaseline())
        confidence = max(1, confidence)
        candidate = next.setting
        candidateAxis = next.axis
        active = next.setting
        probing = true
        candidateTotal = 0L
        candidateSamples = 0
        candidateLatencyTotal = 0L
        candidateLatencySamples = 0
        candidateMarginTotal = 0L
        candidateMarginSamples = 0
        candidateRebufferId = baselineRebufferId
        return Decision(Action.PROBE, active, snapshot(averageBaseline(), now))
    }

    private fun recordCandidate(observation: Observation, now: Long): Decision {
        candidateTotal = saturatedAdd(candidateTotal, observation.bytesPerSecond)
        candidateSamples++
        if (observation.firstByteNanos > 0L) {
            candidateLatencyTotal = saturatedAdd(candidateLatencyTotal, observation.firstByteNanos)
            candidateLatencySamples++
        }
        if (observation.playbackMarginPermille > 0) {
            candidateMarginTotal = saturatedAdd(candidateMarginTotal, observation.playbackMarginPermille.toLong())
            candidateMarginSamples++
        }
        candidateRebufferId = max(candidateRebufferId, observation.rebufferId)
        val baseline = averageBaseline()
        if (materialLoss(baseline, observation.bytesPerSecond) || qualityRegression()) return reject(now)
        if (candidateSamples < REQUIRED_SAMPLES) return Decision.none(active)
        val measured = candidateTotal / candidateSamples
        if ((materialGain(baseline, measured) || healthImproved(measured)) && !qualityRegression()) {
            best = candidate!!
            active = best
            bestBps = measured
            confidence = 1
            cooldown = ACCEPTED_PROBE_COOLDOWN
            probeNotBefore = cooldownDeadline(now, ACCEPTED_PROBE_COOLDOWN_MILLIS)
            nextAxis = candidateAxis
            probing = false
            probeFinished = true
            return Decision(Action.COMMIT, active, snapshot(bestBps, now))
        }
        return reject(now)
    }

    private fun reject(now: Long): Decision {
        active = best
        probing = false
        probeFinished = true
        cooldown = REJECTED_PROBE_COOLDOWN
        probeNotBefore = cooldownDeadline(now, REJECTED_PROBE_COOLDOWN_MILLIS)
        if (candidateAxis == AXIS_COUNT) {
            countDirection = -countDirection
            nextAxis = AXIS_SIZE
        } else {
            sizeDirection = -sizeDirection
            nextAxis = AXIS_COUNT
        }
        confidence = min(MAX_CONFIDENCE, max(1, confidence + 1))
        bestBps = smooth(bestBps, averageBaseline())
        return Decision(Action.ROLLBACK, active, snapshot(bestBps, now))
    }

    fun finish(now: Long): RangeTuningProfile? {
        if (probing) {
            active = best
            probing = false
            cooldown = max(cooldown, 2)
            probeNotBefore = max(probeNotBefore, cooldownDeadline(now, INCOMPLETE_PROBE_COOLDOWN_MILLIS))
            return snapshot(bestBps, now)
        }
        return null
    }

    fun fail(now: Long): RangeTuningProfile? {
        if (probing) {
            active = best
            probing = false
            cooldown = max(cooldown, 2)
            probeNotBefore = max(probeNotBefore, cooldownDeadline(now, INCOMPLETE_PROBE_COOLDOWN_MILLIS))
            if (candidateAxis == AXIS_COUNT) countDirection = -countDirection
            else sizeDirection = -sizeDirection
            nextAxis = if (candidateAxis == AXIS_COUNT) AXIS_SIZE else AXIS_COUNT
            return if (confidence <= 0) null else snapshot(bestBps, now)
        }
        confidence = max(0, confidence - 1)
        return if (confidence == 0) null else snapshot(bestBps, now)
    }

    private fun chooseCandidate(): Candidate? {
        var result = candidateFor(nextAxis)
        if (result != null) return result
        val other = if (nextAxis == AXIS_COUNT) AXIS_SIZE else AXIS_COUNT
        result = candidateFor(other)
        if (result != null) {
            nextAxis = other
            return result
        }
        return null
    }

    private fun candidateFor(axis: Int): Candidate? {
        if (axis == AXIS_COUNT) {
            var direction = countDirection
            var result = Setting(best.ranges + direction, best.rangeBytes)
            if (!bounds.contains(result)) {
                direction = -direction
                result = Setting(best.ranges + direction, best.rangeBytes)
            }
            if (!bounds.contains(result)) return null
            countDirection = direction
            return Candidate(axis, result)
        }
        var direction = sizeDirection
        var bytes = scaledRangeBytes(best.rangeBytes, direction)
        var result = Setting(best.ranges, bytes)
        if (!bounds.contains(result) || bytes == best.rangeBytes) {
            direction = -direction
            bytes = scaledRangeBytes(best.rangeBytes, direction)
            result = Setting(best.ranges, bytes)
        }
        if (!bounds.contains(result) || bytes == best.rangeBytes) return null
        sizeDirection = direction
        return Candidate(axis, result)
    }

    private fun scaledRangeBytes(value: Long, direction: Int): Long {
        if (direction > 0) return if (value > Long.MAX_VALUE / 2L) Long.MAX_VALUE else value * 2L
        return max(1L, value / 2L)
    }

    private fun snapshot(observedBps: Long, now: Long): RangeTuningProfile {
        return RangeTuningProfile(best.ranges, best.rangeBytes,
            max(0L, observedBps), max(1, confidence), max(1L, now),
            nextAxis, countDirection, sizeDirection, max(0, cooldown),
            max(0L, probeNotBefore), 0L)
    }

    private fun averageBaseline(): Long {
        return if (baselineSamples == 0) bestBps else baselineTotal / baselineSamples
    }

    private fun averageBaselineLatency(): Long {
        return if (baselineLatencySamples == 0) 0L else baselineLatencyTotal / baselineLatencySamples
    }

    private fun averageCandidateLatency(): Long {
        return if (candidateLatencySamples == 0) 0L else candidateLatencyTotal / candidateLatencySamples
    }

    private fun averageBaselineMargin(): Int {
        return if (baselineMarginSamples == 0) 0 else (baselineMarginTotal / baselineMarginSamples).toInt()
    }

    private fun averageCandidateMargin(): Int {
        return if (candidateMarginSamples == 0) 0 else (candidateMarginTotal / candidateMarginSamples).toInt()
    }

    private fun qualityRegression(): Boolean {
        if (candidateRebufferId > baselineRebufferId) return true
        val baselineLatency = averageBaselineLatency()
        val candidateLatency = averageCandidateLatency()
        if (baselineLatency > 0L && candidateLatency > baselineLatency + baselineLatency * LATENCY_LOSS_PERCENT / 100L) return true
        val baselineMargin = averageBaselineMargin()
        val candidateMargin = averageCandidateMargin()
        return baselineMargin > 0 && candidateMargin > 0 && candidateMargin + HEALTH_MARGIN_DELTA_PERMILLE < baselineMargin
    }

    private fun healthImproved(candidateBps: Long): Boolean {
        val baselineMargin = averageBaselineMargin()
        val candidateMargin = averageCandidateMargin()
        return baselineMargin > 0 && candidateMargin >= baselineMargin + HEALTH_MARGIN_DELTA_PERMILLE && !materialLoss(averageBaseline(), candidateBps)
    }

    companion object {
        const val AXIS_COUNT = 0
        const val AXIS_SIZE = 1
        const val REQUIRED_SAMPLES = 2
        const val GAIN_PERCENT = 10
        const val LOSS_PERCENT = 30
        const val LATENCY_LOSS_PERCENT = 50
        const val HEALTH_MARGIN_DELTA_PERMILLE = 150
        const val REJECTED_PROBE_COOLDOWN = 8
        const val ACCEPTED_PROBE_COOLDOWN = 1
        const val REJECTED_PROBE_COOLDOWN_MILLIS = 30L * 60L * 1000L
        const val ACCEPTED_PROBE_COOLDOWN_MILLIS = 5L * 60L * 1000L
        const val INCOMPLETE_PROBE_COOLDOWN_MILLIS = 2L * 60L * 1000L
        private const val MAX_CONFIDENCE = 8

        @JvmStatic
        fun materialGain(baseline: Long, candidate: Long): Boolean {
            return baseline <= 0L || candidate >= baseline + baseline * GAIN_PERCENT / 100L
        }

        @JvmStatic
        fun materialLoss(baseline: Long, candidate: Long): Boolean {
            return baseline > 0L && candidate < baseline - baseline * LOSS_PERCENT / 100L
        }

        private fun cooldownDeadline(now: Long, delayMillis: Long): Long {
            val start = max(1L, now)
            return if (start > Long.MAX_VALUE - delayMillis) Long.MAX_VALUE else start + delayMillis
        }

        private fun smooth(previous: Long, sample: Long): Long {
            if (sample <= 0L) return max(0L, previous)
            if (previous <= 0L) return sample
            return previous / 4L * 3L + sample / 4L
        }

        private fun saturatedAdd(left: Long, right: Long): Long {
            if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE
            return left + right
        }

        private fun normalizeAxis(value: Int): Int = if (value == AXIS_SIZE) AXIS_SIZE else AXIS_COUNT
        private fun normalizeDirection(value: Int): Int = if (value < 0) -1 else 1
        private fun clampConfidence(value: Int): Int = max(0, min(MAX_CONFIDENCE, value))
    }
}
