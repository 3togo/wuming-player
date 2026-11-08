package com.wuming.tv.core.playback

import com.wuming.tv.core.playback.fixtures.PostrollEpisodeFixture
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class HlsAppendedAdFilterTest {
    private val fixture = PostrollEpisodeFixture()
    private val base = "https://media.example/"
    private fun filter(playlist: String = fixture.playlist,
                       probe: HlsResolutionAdFilter.Probe = HlsResolutionAdFilter.Probe { fixture.info(fixture.index(it)) }) =
        HlsAppendedAdFilter.filter(base, playlist, probe) { b, r -> URI(b).resolve(r).toString() }

    private fun prepare(playlist: String, probe: HlsResolutionAdFilter.Probe) =
        HlsAdFilterEngine.purify(base, base, playlist, emptyList(), probe) { b, r -> URI(b).resolve(r).toString() }

    private fun segments(playlist: String) = playlist.lines().filter { it.isNotBlank() && !it.startsWith('#') }

    @Test fun capturedEpisodeRemovesOnlyFourAppendedAdsAndRetainsEveryCreditSegmentThroughSharedPipeline() {
        val calls = mutableListOf<String>()
        val session = HlsSegmentProbeSession(emptyMap(), { url, _, _, _ ->
            calls.add(url)
            HlsSegmentProbeSession.Prefix(200, null, fixture.prefix(url))
        })
        val result = prepare(fixture.playlist, session)
        assertEquals(0, result.resolution.removedSegments)
        assertEquals(0, result.insertion.removedSegments)
        assertEquals(1, result.appended.removedBlocks)
        assertEquals(4, result.appended.removedSegments)
        assertEquals(fixture.urls.take(713), segments(result.content))
        assertEquals(2853.68, result.content.lines().filter { it.startsWith("#EXTINF:") }
            .sumOf { it.substringAfter(':').substringBefore(',').toDouble() }, 0.00001)
        assertEquals(1, result.content.lines().count { it == "#EXT-X-ENDLIST" })
        assertTrue(result.content.trimEnd().endsWith("#EXT-X-ENDLIST"))
        assertEquals(7, calls.size)
        assertEquals(7, calls.toSet().size)
        assertEquals(0, filter(result.content) { error("already filtered credits need no probe") }.removedSegments)
    }

    @Test fun actualCreditsRemainIntactWithOrWithoutAPlaylistDiscontinuity() {
        val credits = fixture.creditsOnly
        val validBoundary = credits.replace("#EXTINF:4.000000,\n${fixture.urls[709]}",
            "#EXT-X-DISCONTINUITY\n#EXTINF:4.000000,\n${fixture.urls[709]}")
        for (input in listOf(credits, validBoundary)) {
            assertEquals(input, filter(input) { error("normal credits must not spend probes") }.content)
        }
    }

    @Test fun detectionIsIndependentOfEpisodeNamesHostsDimensionsAndSpecificTimingRates() {
        var renamed = fixture.playlist
        val alternateUrls = fixture.urls.indices.map { "https://another.invalid/path/chunk%03d.ts".format(it) }
        fixture.urls.zip(alternateUrls).forEach { (old, new) -> renamed = renamed.replace(old, new) }
        for ((programmeRate, adRate) in listOf(30.0 to 24.0, 23.976 to 29.97)) {
            val result = filter(renamed) { url ->
                val index = alternateUrls.indexOf(url)
                val info = fixture.info(index)
                HlsResolutionAdFilter.VideoInfo(if (index < 713) 1280 else 640, if (index < 713) 720 else 360,
                    info.ptsSeconds, if (index < 713) programmeRate else adRate)
            }
            assertEquals(alternateUrls.take(713), segments(result.content))
        }
    }

    @Test fun replacementResolvesRelativeProgrammeUrisBeforeAndroidPublishesItAtADifferentUrl() {
        val relative = fixture.playlist.replace("https://media.example/zuimeng-02/", "")
        val result = filter(relative)
        assertEquals(fixture.urls.take(713).map { base + URI(it).path.substringAfterLast('/') }, segments(result.content))
        assertEquals(4, result.removedSegments)
    }

    @Test fun programmeSampleStartsMustSpanAFullMinuteWithoutCountingTheFinalSegmentDuration() {
        val playlist = buildString {
            append("#EXTM3U\n#EXT-X-TARGETDURATION:9\n")
            for (index in 0..10) append("#EXTINF:${if (index == 10) 6 else 7},\np$index.ts\n")
            append("#EXT-X-DISCONTINUITY\n#EXTINF:9,\na.ts\n#EXTINF:4,\nb.ts\n#EXT-X-ENDLIST\n")
        }
        val calls = mutableListOf<String>()
        val result = filter(playlist) { url ->
            val name = URI(url).path.substringAfterLast('/')
            calls.add(name)
            when (name) {
                "a.ts" -> HlsResolutionAdFilter.VideoInfo(1920, 1080, 1.48, 30.0)
                "b.ts" -> HlsResolutionAdFilter.VideoInfo(1920, 1080, 10.48, 30.0)
                else -> {
                    val index = name.removePrefix("p").removeSuffix(".ts").toInt()
                    HlsResolutionAdFilter.VideoInfo(1920, 1080, index * 7 + 1.48, if (index == 1) 0.0 else 25.0)
                }
            }
        }
        assertEquals(playlist, result.content)
        // p2 is only 56 seconds before p10; p1 is the required sample, 63s back.
        assertTrue(calls.contains("p1.ts"))
    }

    @Test fun timestampResetAloneOrTimingChangeAloneDoesNotRemoveAnEnding() {
        for (reset in listOf(false, true)) {
            val result = filter { url ->
                val index = fixture.index(url)
                val info = fixture.info(index)
                if (index < 713) info else HlsResolutionAdFilter.VideoInfo(info.width, info.height,
                    if (reset) info.ptsSeconds else info.ptsSeconds + 2853.68,
                    if (reset) 25.0 else info.nominalFrameRate)
            }
            assertEquals(fixture.playlist, result.content)
        }
    }

    @Test fun keepsSeparatelyEncodedEndingsWithoutTheTruncatedProgrammeAndNewSegmentationCombination() {
        for (input in listOf(
            fixture.playlist.replace("#EXTINF:2.000000,\n${fixture.urls[712]}", "#EXTINF:4.000000,\n${fixture.urls[712]}"),
            fixture.playlist.replace("#EXTINF:6.633333,", "#EXTINF:4.000000,"))) {
            assertEquals(input, filter(input) { error("structural gate must precede probes") }.content)
        }
    }

    @Test fun everyProgrammeAndCandidateSampleMustBeAvailableAndConsistent() {
        val indices = listOf(697, 711, 712, 713, 714, 715, 716)
        for (index in indices) for (failure in listOf("missing", "exception", "pts", "rate", "size", "unknown")) {
            val result = filter { url ->
                val i = fixture.index(url)
                val info = fixture.info(i)
                if (i != index) info else when (failure) {
                    "missing" -> null
                    "exception" -> error("probe timeout")
                    "pts" -> HlsResolutionAdFilter.VideoInfo(info.width, info.height, info.ptsSeconds + 15, info.nominalFrameRate)
                    "rate" -> HlsResolutionAdFilter.VideoInfo(info.width, info.height, info.ptsSeconds, 60.0)
                    "size" -> HlsResolutionAdFilter.VideoInfo(640, 360, info.ptsSeconds, info.nominalFrameRate)
                    else -> HlsResolutionAdFilter.VideoInfo(info.width, info.height, info.ptsSeconds)
                }
            }
            assertEquals("$index $failure", fixture.playlist, result.content)
        }
    }

    @Test fun rejectsUnsupportedOrIncompletePlaylistsBeforeProbing() {
        val inputs = listOf(fixture.playlist.replace("#EXT-X-ENDLIST", ""),
            fixture.playlist.replace("#EXTM3U", "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\""),
            fixture.playlist.replace("#EXTM3U", "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\""),
            fixture.playlist + "#EXTINF:2,\nunfinished.ts\n",
            fixture.playlist.replace("#EXTINF:6.633333,", "#EXTINF:26.633333,"),
            fixture.playlist.replace("#EXT-X-DISCONTINUITY\n", ""))
        for (input in inputs) assertEquals(input, filter(input) { error("unsupported") }.content)
    }

    @Test fun canceledPreparationRetainsAllSegments() {
        val session = HlsSegmentProbeSession(emptyMap(), { _, _, _, _ -> error("must not request") }, { true })
        assertEquals(fixture.playlist, prepare(fixture.playlist, session).content)
    }

    @Test fun replaysOriginalDownloadedTsBytesWhenAvailable() {
        val directory = System.getenv("TV_AD_TAIL_FIXTURE_DIR")
        assumeTrue("Optional original local media", directory != null)
        val root = Path.of(directory)
        val session = HlsSegmentProbeSession(emptyMap(), { url, _, maxBytes, _ ->
            val index = fixture.index(url)
            val bytes = Files.newInputStream(root.resolve("segment-%03d.ts".format(index))).use { it.readNBytes(maxBytes) }
            HlsSegmentProbeSession.Prefix(200, null, bytes)
        })
        val result = prepare(fixture.playlist, session)
        assertEquals(4, result.appended.removedSegments)
        assertEquals(fixture.urls.take(713), segments(result.content))
        assertEquals(7, session.requestCount)
    }
}
