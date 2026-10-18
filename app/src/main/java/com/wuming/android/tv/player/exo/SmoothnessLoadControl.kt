package com.wuming.android.tv.player.exo

import android.app.ActivityManager
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.upstream.DefaultAllocator
import com.wuming.android.tv.App
import com.wuming.android.tv.utils.AppLog
import com.wuming.tv.core.playback.AdaptiveBufferController
import com.wuming.tv.core.playback.PlaybackTransportPlan
import java.util.function.LongSupplier
import java.util.function.Supplier

/** Android allocation and memory ports for the shared progressive VOD buffer controller. */
@UnstableApi
internal class SmoothnessLoadControl private constructor(
    config: AdaptiveBufferController.Config,
    private val memory: Supplier<PlaybackBufferBudget.Memory>,
    private val clock: LongSupplier,
    private val allocator: DefaultAllocator,
    private val initialTargetBytes: Int,
    private val health: PlaybackHealthMonitor,
) : DefaultLoadControl(
    allocator, milliseconds(config.baseResumeUs), DEFAULT_MIN_BUFFER_FOR_LOCAL_PLAYBACK_MS,
    milliseconds(config.maximumTargetUs), DEFAULT_MAX_BUFFER_FOR_LOCAL_PLAYBACK_MS,
    START_PLAYBACK_MS, DEFAULT_BUFFER_FOR_PLAYBACK_FOR_LOCAL_PLAYBACK_MS,
    RESUME_AFTER_REBUFFER_MS, DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_FOR_LOCAL_PLAYBACK_MS,
    initialTargetBytes, false, false, DEFAULT_BACK_BUFFER_DURATION_MS,
    DEFAULT_RETAIN_BACK_BUFFER_FROM_KEYFRAME,
) {
    private val controller = AdaptiveBufferController(config)
    private var targetBytes = initialTargetBytes
    private var nextMemoryCheckMs = 0L
    private val period = Timeline.Period()
    private val window = Timeline.Window()
    private var activePeriodUid: Any? = null
    private var adaptiveActive = false
    private var loaderActive = false

    constructor(preference: Int) : this(preference, PlaybackHealthMonitor())
    constructor(preference: Int, health: PlaybackHealthMonitor) : this(preference,
        Supplier { readMemory() }, LongSupplier { SystemClock.elapsedRealtime() }, health)
    constructor(preference: Int, heapBytes: Long) : this(preference,
        Supplier { PlaybackBufferBudget.Memory(heapBytes, 0, -1, 0, false, false) },
        LongSupplier { SystemClock.elapsedRealtime() })
    constructor(preference: Int, memory: Supplier<PlaybackBufferBudget.Memory>, clock: LongSupplier) :
        this(preference, memory, clock, PlaybackHealthMonitor())
    constructor(preference: Int, memory: Supplier<PlaybackBufferBudget.Memory>, clock: LongSupplier,
                health: PlaybackHealthMonitor) : this(
        AdaptiveBufferController.Config.fromPreference(preference), memory, clock,
        DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE), memory.get().initialBytes(), health,
    )

    override fun onPrepared(playerId: PlayerId) {
        super.onPrepared(playerId)
        if (playerId != PlayerId.PRELOAD) {
            resetAdaptiveState()
            targetBytes = minOf(initialTargetBytes, memory.get().initialBytes())
            nextMemoryCheckMs = clock.asLong + MEMORY_CHECK_INTERVAL_MS
            allocator.setTargetBufferSize(targetBytes)
        }
    }
    override fun onStopped(playerId: PlayerId) {
        super.onStopped(playerId)
        if (playerId != PlayerId.PRELOAD) resetAdaptiveState()
    }
    override fun onReleased(playerId: PlayerId) {
        super.onReleased(playerId)
        if (playerId != PlayerId.PRELOAD) resetAdaptiveState()
    }
    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        checkMemory()
        val media3Decision = super.shouldContinueLoading(parameters)
        // Media3 shares the backing allocator with preloads, but its load control
        // budgets allocations per player. Keep our adaptive cap per player too.
        val byteLimitReached = getAllocator(parameters.playerId).totalBytesAllocated >= targetBytes
        // Preload callbacks share this load control but must not reset or advance
        // the active video's adaptive hysteresis state.
        if (parameters.playerId == PlayerId.PRELOAD) return media3Decision && !byteLimitReached
        val periodUid = adaptivePeriodUid(parameters)
        if (periodUid == null) {
            if (adaptiveActive) resetAdaptiveState()
            loaderActive = media3Decision && !byteLimitReached
            return loaderActive
        }
        if (!adaptiveActive || activePeriodUid != periodUid) {
            controller.reset()
            adaptiveActive = true
            activePeriodUid = periodUid
            loaderActive = media3Decision
        }
        val decision = controller.update(AdaptiveBufferController.Sample(clock.asLong,
            parameters.bufferedDurationUs, parameters.playbackPositionUs, parameters.playbackSpeed,
            parameters.playWhenReady, parameters.rebuffering, parameters.lastRebufferRealtimeMs), loaderActive)
        health.record(SystemClock.elapsedRealtime(), decision.downloadMarginPermille,
            parameters.lastRebufferRealtimeMs)
        val previous = loaderActive
        // One time hysteresis loop lets a recovery refill reach its adaptive watermark.
        // The configured maximum and RAM byte budget remain hard limits.
        loaderActive = !byteLimitReached && decision.loading
        if (decision.action != AdaptiveBufferController.Action.NONE || loaderActive != previous) {
            AppLog.i(TAG, "event=" + event(decision, loaderActive, previous)
                + ",bufferedMs=" + parameters.bufferedDurationUs / 1_000L
                + ",resumeMs=" + decision.resumeWatermarkUs / 1_000L
                + ",targetMs=" + decision.targetUs / 1_000L
                + ",marginPermille=" + decision.downloadMarginPermille
                + ",health=" + decision.health + ",media3Loading=" + media3Decision)
        }
        return loaderActive
    }
    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
        super.shouldStartPlayback(parameters) ||
            (parameters.bufferedDurationUs > 0L &&
                getAllocator(parameters.playerId).totalBytesAllocated >= targetBytes)

    private fun checkMemory() {
        val now = clock.asLong
        if (now < nextMemoryCheckMs) return
        nextMemoryCheckMs = now + MEMORY_CHECK_INTERVAL_MS
        val constrainedBytes = memory.get().constrainBytes(targetBytes)
        if (constrainedBytes != targetBytes) {
            targetBytes = constrainedBytes
            allocator.setTargetBufferSize(targetBytes)
            allocator.trim()
        }
    }
    private fun adaptivePeriodUid(parameters: LoadControl.Parameters): Any? {
        if (parameters.playerId == PlayerId.PRELOAD || parameters.targetLiveOffsetUs != C.TIME_UNSET
            || parameters.timeline.isEmpty) return null
        val index = parameters.timeline.getIndexOfPeriod(parameters.mediaPeriodId.periodUid)
        if (index == C.INDEX_UNSET) return null
        parameters.timeline.getPeriod(index, period)
        parameters.timeline.getWindow(period.windowIndex, window)
        if (window.isLive) return null
        val local = window.mediaItem.localConfiguration ?: return null
        if (!supportsAdaptiveBuffer(local.uri.toString(), local.mimeType, false)) return null
        return parameters.mediaPeriodId.periodUid
    }
    private fun resetAdaptiveState() {
        controller.reset()
        health.clear()
        activePeriodUid = null
        adaptiveActive = false
        loaderActive = false
    }
    companion object {
        private const val TAG = "AdaptiveBuffer"
        private const val START_PLAYBACK_MS = 3_000
        private const val RESUME_AFTER_REBUFFER_MS = 8_000
        private const val MEMORY_CHECK_INTERVAL_MS = 5_000L
        @JvmStatic fun bufferBudgetBytes(heapBytes: Long): Int =
            PlaybackBufferBudget.Memory(heapBytes, 0, -1, 0, false, false).initialBytes()
        @JvmStatic fun supportsAdaptiveBuffer(url: String?, mimeType: String?, live: Boolean): Boolean =
            !live && PlaybackTransportPlan.inferKind(url, mimeType, false) == PlaybackTransportPlan.Kind.PROGRESSIVE
        private fun milliseconds(microseconds: Long): Int = minOf(Int.MAX_VALUE.toLong(), microseconds / 1_000L).toInt()
        private fun readMemory(): PlaybackBufferBudget.Memory {
            val runtime = Runtime.getRuntime()
            val manager = App.get().getSystemService(ActivityManager::class.java)
            val info = ActivityManager.MemoryInfo()
            manager?.getMemoryInfo(info)
            return PlaybackBufferBudget.Memory(runtime.maxMemory(), runtime.totalMemory() - runtime.freeMemory(),
                if (manager == null) -1L else info.availMem, info.threshold,
                manager?.isLowRamDevice == true, info.lowMemory)
        }
        private fun event(decision: AdaptiveBufferController.Decision, loading: Boolean, previous: Boolean): String =
            when (decision.action) {
                AdaptiveBufferController.Action.RAISE_WEAK -> "target-raise-weak"
                AdaptiveBufferController.Action.RAISE_REBUFFER -> "target-raise-rebuffer"
                AdaptiveBufferController.Action.LOWER_SMOOTH -> "target-lower-smooth"
                else -> if (loading != previous) { if (loading) "loading-resume" else "loading-cap" } else "state"
            }
    }
}
