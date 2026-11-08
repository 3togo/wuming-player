package com.wuming.tv.core.catalog

import com.wuming.tv.core.playback.PlaybackState
import java.util.function.LongSupplier
import org.junit.Assert.*
import org.junit.Test

class PlaybackPreparationTest {
    private val first = EpisodePlaybackKey(1, 1, "source", "series", "line", "one")
    private val next = first.copy(episodeId = "two")
    private val active = PlaybackState(positionSeconds = 85.0, durationSeconds = 100.0)
    private val result = CatalogPlayback(true, "https://media.example/two", format = "mp4")

    @Test fun unknownResolverIsNeverExecutedEvenWithAStreamingNameAndHttpId() {
        val executor = object : CatalogSourceExecutor {
            override val source = CatalogSource("direct-stream", "Known streaming")
            override fun search(query: String) = emptyList<CatalogItem>()
            override fun detail(id: String) = CatalogDetail()
            override fun play(flag: String, id: String): CatalogPlayback = error("Unexpected provider execution")
        }
        assertNull(ExecutorCatalogGateway(listOf(executor)).prewarm(executor.source.key, "hls", "https://cdn.example/video.m3u8"))
        assertEquals(PlaybackPreparationSafety.UNKNOWN, PlaybackPreparationPolicy.classify(false, "hls", "https://cdn.example/video.m3u8"))
    }

    @Test fun cloudEvidenceOverridesReviewedResolverCapability() {
        for ((flag, id, name) in listOf(
            Triple("夸克智", "opaque-id", ""), Triple("hls", "https://pan.quark.cn/s/test", ""),
            Triple("hls", "https://cdn.example/video.mp4", "阿里原画"), Triple("hls", "opendrive://episode", ""),
        )) assertEquals(PlaybackPreparationSafety.CLOUD, PlaybackPreparationPolicy.classify(true, flag, id, name))
        val session = EpisodePrewarmSession()
        session.begin(first)
        assertNull(session.observe(first, next, active, true, "夸克原画"))
    }

    @Test fun preparationIsActiveEnabledAndOneShotAndFullResultIsConsumedOnce() {
        val session = EpisodePrewarmSession()
        session.begin(first)
        assertNull(session.observe(first, next, active, false))
        assertNull(session.observe(first, next, active.copy(positionSeconds = 79.0), true))
        assertNull(session.observe(first, next, active.copy(paused = true), true))
        assertNull(session.observe(first, next, active.copy(buffering = true), true))
        val request = requireNotNull(session.observe(first, next, active, true))
        assertNull(session.observe(first, next, active, true))
        val headers = mutableMapOf("X-Episode" to "two")
        session.complete(request, result.copy(headers = headers))
        headers.clear()
        assertEquals(result.copy(headers = mapOf("X-Episode" to "two")), session.begin(next))
        assertNull(session.begin(next))
    }

    @Test fun expiryCancellationAndSelectionChangesRejectOldResults() {
        var now = 0L
        val session = EpisodePrewarmSession(LongSupplier { now })
        session.begin(first)
        val request = requireNotNull(session.observe(first, next, active, true))
        session.complete(request, result)
        now = 60_000
        assertNull(session.begin(next))
        session.begin(first)
        val cancelled = requireNotNull(session.observe(first, next, active, true))
        session.invalidate()
        assertFalse(session.isCurrent(cancelled))
        session.complete(cancelled, result)
        assertNull(session.begin(next))
        for (other in listOf(next.copy(sourceKey = "other"), next.copy(itemId = "other"), next.copy(lineFlag = "other"),
            next.copy(catalogGeneration = 2), next.copy(accountGeneration = 2))) {
            session.begin(first)
            val pending = requireNotNull(session.observe(first, next, active, true))
            session.complete(pending, result)
            assertNull(session.begin(other))
            session.complete(pending, result)
            assertNull(session.begin(next))
        }
    }

    @Test fun invalidResultsAndUnrelatedSelectionCannotPrepare() {
        val session = EpisodePrewarmSession()
        session.begin(first)
        assertNull(session.observe(first.copy(itemId = "other"), next, active, true))
        assertNull(session.observe(first, next.copy(itemId = "other"), active, true))
        assertNull(session.observe(first, next, active.copy(durationSeconds = Double.NaN), true))
        val request = requireNotNull(session.observe(first, next, active, true))
        session.complete(request, result.copy(supported = false))
        session.complete(request, result.copy(url = " "))
        session.complete(request, null)
        assertNull(session.begin(next))
    }
}
