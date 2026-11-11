package com.wuming.tv.core.catalog

import com.wuming.tv.core.cache.ExpiringLruCache
import com.wuming.tv.core.cloud.CloudDriveDetector
import com.wuming.tv.core.cloud.CloudDriveProviderKind
import com.wuming.tv.core.playback.PlaybackSessionProgress
import com.wuming.tv.core.playback.PlaybackState
import java.util.function.LongSupplier

/** Supplied by reviewed resolver code, never inferred from source names or downloaded metadata. */
enum class PlaybackPreparationSafety { UNKNOWN, CLOUD, READ_ONLY_STREAM }

object PlaybackPreparationPolicy {
    @JvmStatic
    fun classify(reviewedReadOnlyResolver: Boolean, flag: String, id: String, lineName: String = ""): PlaybackPreparationSafety =
        if (CloudDriveDetector.detect(flag, id, lineName) != CloudDriveProviderKind.NONE || id.startsWith("opendrive:", true)) {
            PlaybackPreparationSafety.CLOUD
        } else if (reviewedReadOnlyResolver) PlaybackPreparationSafety.READ_ONLY_STREAM else PlaybackPreparationSafety.UNKNOWN
}

/** Includes runtime/account revisions so signed results cannot cross a configuration or login change. */
data class EpisodePlaybackKey(
    val catalogGeneration: Long,
    val accountGeneration: Long,
    val sourceKey: String,
    val itemId: String,
    val lineFlag: String,
    val episodeId: String,
)

/** One bounded lookahead. Hosts supply scheduling and the explicitly safe gateway operation. */
class EpisodePrewarmSession @JvmOverloads constructor(
    clock: LongSupplier = LongSupplier { System.nanoTime() / 1_000_000 },
) {
    data class Request internal constructor(val target: EpisodePlaybackKey, internal val generation: Long)
    private val prepared = ExpiringLruCache<EpisodePlaybackKey, CatalogPlayback>(1, 60_000, clock)
    private val progress = PlaybackSessionProgress()
    private var current: EpisodePlaybackKey? = null
    private var generation = 0L

    @Synchronized fun begin(selection: EpisodePlaybackKey): CatalogPlayback? {
        val result = prepared.remove(selection)
        invalidate()
        current = selection
        return result
    }

    @Synchronized fun invalidate() {
        generation++
        current = null
        prepared.clear()
        progress.reset()
    }

    @Synchronized fun observe(selection: EpisodePlaybackKey, next: EpisodePlaybackKey, state: PlaybackState, enabled: Boolean, lineName: String = ""): Request? {
        if (!enabled || selection != current || next.copy(episodeId = selection.episodeId) != selection) return null
        if (PlaybackPreparationPolicy.classify(false, next.lineFlag, next.episodeId, lineName) == PlaybackPreparationSafety.CLOUD) return null
        if (!state.positionSeconds.isFinite() || !state.durationSeconds.isFinite()) return null
        val tick = progress.observe((state.positionSeconds * 1000).toLong(), (state.durationSeconds * 1000).toLong(), 0,
            !state.paused && !state.buffering)
        return if (tick.prewarmNext) Request(next, generation) else null
    }

    @Synchronized fun isCurrent(request: Request): Boolean = request.generation == generation && current != null

    @Synchronized fun complete(request: Request, result: CatalogPlayback?) {
        if (!isCurrent(request) || result == null || !result.supported || result.url.isBlank()) return
        prepared.put(request.target, result.copy(headers = result.headers.toMap()))
    }
}
