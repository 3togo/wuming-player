package com.wuming.tv.core.playback

import kotlin.math.max
import kotlin.math.min

/**
 * Slow, bounded buffer adaptation driven by media downloaded while the loader is active.
 *
 * The controller owns only the soft low/high watermarks. Media3 still owns allocation, hard
 * limits and the final permission to load. Download margin is calculated from real buffer growth
 * plus real playhead movement, divided by the media that should be consumed at the selected
 * playback speed. Loader-off pulses, pauses, seeks and rebuffer intervals are excluded.
 */
class AdaptiveBufferController(private val config: Config) {

    enum class Action { NONE, RAISE_WEAK, RAISE_REBUFFER, LOWER_SMOOTH }
    enum class Health { UNKNOWN, WEAK, MARGINAL, SMOOTH }

    class Config(val baseResumeUs: Long, val baseTargetUs: Long, val maximumTargetUs: Long) {
        init {
            if (baseResumeUs <= 0L || baseTargetUs <= baseResumeUs || maximumTargetUs < baseTargetUs) {
                throw IllegalArgumentException("Invalid adaptive buffer bounds")
            }
        }

        @JvmName("baseResumeUs")
        fun baseResumeUsAccessor(): Long = baseResumeUs
        @JvmName("baseTargetUs")
        fun baseTargetUsAccessor(): Long = baseTargetUs
        @JvmName("maximumTargetUs")
        fun maximumTargetUsAccessor(): Long = maximumTargetUs

        companion object {
            @JvmStatic
            fun fromPreference(preference: Int): Config {
                val level = max(1, min(10, preference))
                val baseResumeUs = (8L + min(4, level)) * SECOND_US
                val baseTargetUs = (18L + level * 2L) * SECOND_US
                val maximumTargetUs = min(55L * SECOND_US, baseTargetUs + 15L * SECOND_US)
                return Config(baseResumeUs, baseTargetUs, maximumTargetUs)
            }
        }
    }

    class Sample(
        val nowMillis: Long, val bufferedDurationUs: Long, val playbackPositionUs: Long,
        val playbackSpeed: Float, val playWhenReady: Boolean, val rebuffering: Boolean,
        val lastRebufferRealtimeMs: Long
    ) {
        @JvmName("nowMillis")
        fun nowMillisAccessor(): Long = nowMillis
        @JvmName("bufferedDurationUs")
        fun bufferedDurationUsAccessor(): Long = bufferedDurationUs
        @JvmName("playbackPositionUs")
        fun playbackPositionUsAccessor(): Long = playbackPositionUs
        @JvmName("playbackSpeed")
        fun playbackSpeedAccessor(): Float = playbackSpeed
        @JvmName("playWhenReady")
        fun playWhenReadyAccessor(): Boolean = playWhenReady
        @JvmName("rebuffering")
        fun rebufferingAccessor(): Boolean = rebuffering
        @JvmName("lastRebufferRealtimeMs")
        fun lastRebufferRealtimeMsAccessor(): Long = lastRebufferRealtimeMs
    }

    class Decision(
        val loading: Boolean, val resumeWatermarkUs: Long, val targetUs: Long,
        val downloadMarginPermille: Int, val health: Health, val action: Action
    ) {
        @JvmName("loading")
        fun loadingAccessor(): Boolean = loading
        @JvmName("resumeWatermarkUs")
        fun resumeWatermarkUsAccessor(): Long = resumeWatermarkUs
        @JvmName("targetUs")
        fun targetUsAccessor(): Long = targetUs
        @JvmName("downloadMarginPermille")
        fun downloadMarginPermilleAccessor(): Int = downloadMarginPermille
        @JvmName("health")
        fun healthAccessor(): Health = health
        @JvmName("action")
        fun actionAccessor(): Action = action
    }

    private var targetUs: Long = 0
    private var sampleAtMillis: Long = 0
    private var sampleBufferUs: Long = 0
    private var samplePositionUs: Long = 0
    private var lastControlAtMillis: Long = 0
    private var lastEvidenceAtMillis: Long = 0
    private var lastRebufferRealtimeMs: Long = 0
    private var activeEvidenceMillis: Long = 0
    private var downloadMarginPermille: Int = 0
    private var hasDownloadMargin: Boolean = false
    private var smoothWindows: Int = 0
    private var weakWindows: Int = 0
    private var loading: Boolean = true

    init { reset() }

    fun update(sample: Sample, loaderWasActive: Boolean): Decision {
        if (clockMovedBackwards(sample.nowMillis)) resetSamplingClock(sample.nowMillis)
        var action = observeRebuffer(sample)
        observeDownloadMargin(sample, loaderWasActive)
        if (lastControlAtMillis == 0L) lastControlAtMillis = sample.nowMillis
        if (sample.nowMillis - lastControlAtMillis >= CONTROL_INTERVAL_MILLIS) {
            val periodic = adapt(sample.nowMillis, sample.bufferedDurationUs)
            if (action == Action.NONE) action = periodic
            lastControlAtMillis = sample.nowMillis
            activeEvidenceMillis = 0L
        }
        val resumeWatermark = resumeWatermarkUs()
        if (sample.bufferedDurationUs >= 0L) {
            if (!sample.playWhenReady) {
                loading = sample.bufferedDurationUs < targetUs
            } else if (sample.bufferedDurationUs <= resumeWatermark) {
                loading = true
            } else if (sample.bufferedDurationUs >= targetUs) {
                loading = false
            }
        }
        return Decision(loading, resumeWatermark, targetUs, downloadMarginPermille, health(), action)
    }

    fun reset() {
        targetUs = config.baseTargetUs
        sampleAtMillis = 0L
        sampleBufferUs = 0L
        samplePositionUs = Long.MIN_VALUE
        lastControlAtMillis = 0L
        lastEvidenceAtMillis = 0L
        lastRebufferRealtimeMs = Long.MIN_VALUE
        activeEvidenceMillis = 0L
        downloadMarginPermille = 0
        hasDownloadMargin = false
        smoothWindows = 0
        weakWindows = 0
        loading = true
    }

    fun targetUs(): Long = targetUs

    fun resumeWatermarkUs(): Long {
        val recoveryReserveUs = targetUs - config.baseTargetUs
        return config.baseResumeUs + recoveryReserveUs / 2L
    }

    private fun observeRebuffer(sample: Sample): Action {
        if (!sample.rebuffering || sample.lastRebufferRealtimeMs <= 0L || sample.lastRebufferRealtimeMs == lastRebufferRealtimeMs) {
            return Action.NONE
        }
        lastRebufferRealtimeMs = sample.lastRebufferRealtimeMs
        val previous = targetUs
        targetUs = min(config.maximumTargetUs, targetUs + REBUFFER_STEP_US)
        resetEvidence()
        return if (targetUs > previous) Action.RAISE_REBUFFER else Action.NONE
    }

    private fun observeDownloadMargin(sample: Sample, loaderWasActive: Boolean) {
        if (!sample.playWhenReady) {
            clearSampleAnchor()
            activeEvidenceMillis = 0L
            return
        }
        if (!loaderWasActive || sample.rebuffering || sample.playbackSpeed <= 0f
            || sample.bufferedDurationUs < 0L || sample.playbackPositionUs < 0L) {
            clearSampleAnchor()
            if (sample.rebuffering) activeEvidenceMillis = 0L
            return
        }
        if (sampleAtMillis == 0L) {
            setSampleAnchor(sample)
            return
        }
        val elapsedMillis = sample.nowMillis - sampleAtMillis
        if (elapsedMillis < MIN_SAMPLE_MILLIS) return
        if (elapsedMillis > MAX_SAMPLE_MILLIS || discontinuity(sample, elapsedMillis)) {
            setSampleAnchor(sample)
            return
        }
        val requiredMediaUs = max(1L, (elapsedMillis * 1_000L * sample.playbackSpeed).toLong())
        val playheadAdvanceUs = max(0L, sample.playbackPositionUs - samplePositionUs)
        val bufferGrowthUs = sample.bufferedDurationUs - sampleBufferUs
        var downloadedMediaUs = max(0L, bufferGrowthUs + playheadAdvanceUs)
        downloadedMediaUs = min(requiredMediaUs * 4L, downloadedMediaUs)
        val currentPermille = (downloadedMediaUs * 1_000L / requiredMediaUs).toInt()
        downloadMarginPermille = if (!hasDownloadMargin) currentPermille
            else (downloadMarginPermille * 3 + currentPermille) / 4
        hasDownloadMargin = true
        activeEvidenceMillis = min(CONTROL_INTERVAL_MILLIS, activeEvidenceMillis + elapsedMillis)
        setSampleAnchor(sample)
    }

    private fun adapt(nowMillis: Long, bufferedDurationUs: Long): Action {
        if (activeEvidenceMillis < MIN_ACTIVE_EVIDENCE_MILLIS) return Action.NONE
        if (lastEvidenceAtMillis > 0L && nowMillis - lastEvidenceAtMillis > EVIDENCE_EXPIRY_MILLIS) {
            smoothWindows = 0
            weakWindows = 0
        }
        lastEvidenceAtMillis = nowMillis
        val smooth = downloadMarginPermille >= SMOOTH_MARGIN_PERMILLE && bufferedDurationUs >= resumeWatermarkUs()
        val weak = hasDownloadMargin && downloadMarginPermille < WEAK_MARGIN_PERMILLE && bufferedDurationUs < targetUs
        smoothWindows = if (smooth) min(SMOOTH_WINDOWS_TO_LOWER, smoothWindows + 1) else 0
        weakWindows = if (weak) min(WEAK_WINDOWS_TO_RAISE, weakWindows + 1) else 0
        if (weakWindows >= WEAK_WINDOWS_TO_RAISE && targetUs < config.maximumTargetUs) {
            targetUs = min(config.maximumTargetUs, targetUs + TARGET_STEP_US)
            resetEvidence()
            return Action.RAISE_WEAK
        }
        if (smoothWindows >= SMOOTH_WINDOWS_TO_LOWER && targetUs > config.baseTargetUs) {
            targetUs = max(config.baseTargetUs, targetUs - TARGET_STEP_US)
            resetEvidence()
            return Action.LOWER_SMOOTH
        }
        return Action.NONE
    }

    private fun health(): Health {
        if (!hasDownloadMargin) return Health.UNKNOWN
        if (downloadMarginPermille < WEAK_MARGIN_PERMILLE) return Health.WEAK
        if (downloadMarginPermille < SMOOTH_MARGIN_PERMILLE) return Health.MARGINAL
        return Health.SMOOTH
    }

    private fun discontinuity(sample: Sample, elapsedMillis: Long): Boolean {
        if (samplePositionUs == Long.MIN_VALUE) return false
        val actualAdvanceUs = sample.playbackPositionUs - samplePositionUs
        val expectedAdvanceUs = (elapsedMillis * 1_000L * sample.playbackSpeed).toLong()
        return actualAdvanceUs < -500_000L || actualAdvanceUs > expectedAdvanceUs + 2L * SECOND_US
    }

    private fun clockMovedBackwards(nowMillis: Long): Boolean {
        return (sampleAtMillis > 0L && nowMillis < sampleAtMillis) || (lastControlAtMillis > 0L && nowMillis < lastControlAtMillis)
    }

    private fun resetSamplingClock(nowMillis: Long) {
        clearSampleAnchor()
        activeEvidenceMillis = 0L
        lastControlAtMillis = nowMillis
        lastEvidenceAtMillis = 0L
        smoothWindows = 0
        weakWindows = 0
    }

    private fun resetEvidence() {
        smoothWindows = 0
        weakWindows = 0
        activeEvidenceMillis = 0L
        lastEvidenceAtMillis = 0L
        clearSampleAnchor()
    }

    private fun setSampleAnchor(sample: Sample) {
        sampleAtMillis = sample.nowMillis
        sampleBufferUs = sample.bufferedDurationUs
        samplePositionUs = sample.playbackPositionUs
    }

    private fun clearSampleAnchor() {
        sampleAtMillis = 0L
        sampleBufferUs = 0L
        samplePositionUs = Long.MIN_VALUE
    }

    companion object {
        const val SECOND_US = 1_000_000L
        private const val MIN_SAMPLE_MILLIS = 500L
        private const val MAX_SAMPLE_MILLIS = 5_000L
        private const val CONTROL_INTERVAL_MILLIS = 5_000L
        private const val MIN_ACTIVE_EVIDENCE_MILLIS = 1_500L
        private const val EVIDENCE_EXPIRY_MILLIS = 30_000L
        private const val SMOOTH_MARGIN_PERMILLE = 1_300
        private const val WEAK_MARGIN_PERMILLE = 1_050
        private const val WEAK_WINDOWS_TO_RAISE = 2
        private const val SMOOTH_WINDOWS_TO_LOWER = 6
        private const val TARGET_STEP_US = 5L * SECOND_US
        private const val REBUFFER_STEP_US = 10L * SECOND_US
    }
}
