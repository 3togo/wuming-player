package com.wuming.android.tv.ui.base

/** Estimates readiness from buffer growth, with a provisional countdown before measurable growth. */
class BufferingCountdown {
    companion object {
        const val INITIAL_TARGET_MILLIS = 3_000L
        const val REBUFFER_TARGET_MILLIS = 8_000L
        const val DEFAULT_ETA_SECONDS = 60L
        const val UNKNOWN_ETA = -1L
    }

    private var startedAtMillis = -1L
    private var sampledAtMillis = -1L
    private var sampledBufferMillis = -1L
    private var fillRate = 0.0
    private var positiveSamples = 0
    private var stalledSamples = 0

    fun start(nowMillis: Long) {
        if (startedAtMillis < 0) startedAtMillis = maxOf(0, nowMillis)
    }

    fun update(
        nowMillis: Long,
        bufferedDurationMillis: Long,
        targetMillis: Long,
    ): Status {
        start(nowMillis)
        val now = maxOf(startedAtMillis, nowMillis)
        val buffered = maxOf(0, bufferedDurationMillis)
        val target = maxOf(1, targetMillis)
        if (sampledAtMillis >= 0 && now > sampledAtMillis) {
            val elapsed = now - sampledAtMillis
            val growth = buffered - sampledBufferMillis
            if (growth < -500) {
                clearRate()
            } else if (elapsed >= 500) {
                if (growth > 0) {
                    val sampleRate = growth.toDouble() / elapsed
                    fillRate = if (fillRate == 0.0) sampleRate else fillRate * 0.7 + sampleRate * 0.3
                    positiveSamples++
                    stalledSamples = 0
                } else if (++stalledSamples >= 2) {
                    clearRate()
                }
            }
        }
        sampledAtMillis = now
        sampledBufferMillis = buffered
        val remaining = maxOf(0, target - buffered)
        val elapsedSeconds = (now - startedAtMillis) / 1_000
        var eta = maxOf(1, DEFAULT_ETA_SECONDS - elapsedSeconds)
        if (remaining == 0L) {
            eta = 0
        } else if (positiveSamples >= 1 && fillRate > 0.0) {
            eta = maxOf(1, minOf(599, Math.ceil(remaining / fillRate / 1_000.0).toLong()))
        }
        return Status(elapsedSeconds, eta, remaining == 0L)
    }

    fun reset() {
        startedAtMillis = -1
        sampledAtMillis = -1
        sampledBufferMillis = -1
        clearRate()
    }

    private fun clearRate() {
        fillRate = 0.0
        positiveSamples = 0
        stalledSamples = 0
    }

    @JvmRecord data class Status(
        val elapsedSeconds: Long,
        val etaSeconds: Long,
        val finishing: Boolean,
    ) {
        fun hasEstimate(): Boolean = etaSeconds != UNKNOWN_ETA
    }
}
