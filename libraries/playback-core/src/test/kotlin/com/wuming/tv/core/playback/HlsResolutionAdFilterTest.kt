package com.wuming.tv.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

class HlsResolutionAdFilterTest {
    private val base = "https://media.example/hls/"
    private val features = HashMap<String, HlsResolutionAdFilter.VideoInfo>()

    private fun playlist(): String {
        features[base + "before.ts"] = HlsResolutionAdFilter.VideoInfo(1280, 720, 100.0)
        features[base + "a.ts"] = HlsResolutionAdFilter.VideoInfo(1920, 1080, 1.48)
        features[base + "b.ts"] = HlsResolutionAdFilter.VideoInfo(1920, 1080, 8.44)
        features[base + "c.ts"] = HlsResolutionAdFilter.VideoInfo(1920, 1080, 12.44)
        features[base + "d.ts"] = HlsResolutionAdFilter.VideoInfo(1920, 1080, 15.0)
        features[base + "after.ts"] = HlsResolutionAdFilter.VideoInfo(1280, 720, 160.0)
        return "#EXTM3U\n#EXT-X-TARGETDURATION:60\n#EXT-X-MEDIA-SEQUENCE:20\n" +
            "#EXT-X-DISCONTINUITY\n#EXTINF:60,\nbefore.ts\n" +
            "#EXT-X-DISCONTINUITY\n#EXTINF:6.96,\na.ts\n#EXTINF:4,\nb.ts\n" +
            "#EXTINF:2.56,\nc.ts\n#EXTINF:3.84,\nd.ts\n" +
            "#EXT-X-DISCONTINUITY\n#EXTINF:60,\nafter.ts\n#EXT-X-ENDLIST\n"
    }

    private fun filter(playlist: String): HlsResolutionAdFilter.Result {
        return HlsResolutionAdFilter.filter(base, playlist, { url -> features[url] }
        ) { b, reference -> URI.create(b).resolve(reference).toString() }
    }

    @Test fun confirmsHigherResolutionIslandWithResetAndProgrammeReturnWithoutFpsTest() {
        val result = filter(playlist())
        assertEquals(1, result.removedBlocks)
        assertEquals(4, result.removedSegments)
        assertTrue(result.content.contains(base + "before.ts"))
        assertTrue(result.content.contains(base + "after.ts"))
        assertFalse(result.content.contains("#EXTINF:6.96"))
        assertTrue(result.content.contains("#EXT-X-MEDIA-SEQUENCE:20"))
        assertTrue(result.content.contains("#EXT-X-ENDLIST"))
        assertEquals(0, filter(result.content).removedSegments)
    }

    @Test fun resolutionChangeAloneDoesNotConfirmAnAd() {
        val p = playlist()
        for (name in arrayOf("a.ts", "b.ts", "c.ts", "d.ts")) {
            val old = features[base + name]!!
            features[base + name] = HlsResolutionAdFilter.VideoInfo(1920, 1080, old.ptsSeconds + 158.52)
        }
        assertEquals(p, filter(p).content)
    }

    @Test fun resetCannotOverrideMissingResolutionChange() {
        val p = playlist()
        features.replaceAll { _, f -> HlsResolutionAdFilter.VideoInfo(1280, 720, f.ptsSeconds) }
        assertEquals(p, filter(p).content)
    }

    @Test fun suddenResolutionReductionWithResetAndProgrammeReturnConfirmsAnAd() {
        val p = playlist()
        for (name in arrayOf("a.ts", "b.ts", "c.ts", "d.ts")) {
            val old = features[base + name]!!
            features[base + name] = HlsResolutionAdFilter.VideoInfo(640, 360, old.ptsSeconds)
        }
        assertEquals(4, filter(p).removedSegments)
    }

    @Test fun resolutionReductionWithoutTimestampResetRetainsTheProgramme() {
        val p = playlist()
        for (name in arrayOf("a.ts", "b.ts", "c.ts", "d.ts")) {
            val old = features[base + name]!!
            features[base + name] = HlsResolutionAdFilter.VideoInfo(640, 360, old.ptsSeconds + 158.52)
        }
        assertEquals(p, filter(p).content)
    }

    @Test fun mixedCandidateResolutionsAreRetainedEvenWhenOneSegmentIsLower() {
        val p = playlist()
        features[base + "a.ts"] = HlsResolutionAdFilter.VideoInfo(640, 360, 1.48)
        assertEquals(p, filter(p).content)
    }

    @Test fun requiresProgrammeResolutionAndTimelineToReturn() {
        val p = playlist()
        features[base + "after.ts"] = HlsResolutionAdFilter.VideoInfo(1920, 1080, 160.0)
        assertEquals(p, filter(p).content)
        features[base + "after.ts"] = HlsResolutionAdFilter.VideoInfo(1280, 720, 177.36)
        assertEquals(p, filter(p).content)
    }

    @Test fun rejectsMixedOrUnknownAdSegmentFeatures() {
        val p = playlist()
        features[base + "c.ts"] = HlsResolutionAdFilter.VideoInfo(1280, 720, 12.44)
        assertEquals(p, filter(p).content)
        features.remove(base + "c.ts")
        assertEquals(p, filter(p).content)
    }

    @Test fun rejectsNonContinuousAdTimeline() {
        val p = playlist()
        features[base + "c.ts"] = HlsResolutionAdFilter.VideoInfo(1920, 1080, 70.0)
        assertEquals(p, filter(p).content)
    }

    @Test fun leavesPlaybackUnchangedWhenProbingFails() {
        val p = playlist()
        val result = HlsResolutionAdFilter.filter(base, p, { _ -> throw java.io.IOException("timeout") }
        ) { b, reference -> URI.create(b).resolve(reference).toString() }
        assertEquals(p, result.content)
    }

    @Test fun skipsLiveEncryptedByteRangeAndFragmentedPlaylistsWithoutNetwork() {
        val p = playlist()
        val probes = AtomicInteger()
        for (input in arrayOf(p.replace("#EXT-X-ENDLIST", ""),
            p.replace("#EXTM3U", "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\""),
            p.replace("#EXTM3U", "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\""),
            p.replace("#EXTM3U", "#EXTM3U\n#EXT-X-BYTERANGE:200@0"))) {
            val result = HlsResolutionAdFilter.filter(base, input, { _ -> probes.incrementAndGet(); null }
            ) { b, reference -> URI.create(b).resolve(reference).toString() }
            assertEquals(input, result.content)
        }
        assertEquals(0, probes.get())
    }

    @Test fun leavesLongUnboundedOrMalformedCandidatesUnchanged() {
        val p = playlist()
        for (input in arrayOf(p.replace("#EXTINF:6.96", "#EXTINF:600"),
            p.replace("#EXT-X-DISCONTINUITY\n#EXTINF:6.96", "#EXTINF:6.96"),
            p.replace("#EXTINF:4,", "#EXTINF:NaN,"), p.replace("a.ts\n", ""))) {
            assertEquals(input, filter(input).content)
        }
    }

    @Test fun suddenChangeDoesNotRequireBothDimensionsToIncrease() {
        for ((width, height) in listOf(2560 to 600, 1200 to 1440, 1280 to 600)) {
            val p = playlist()
            for (name in arrayOf("a.ts", "b.ts", "c.ts", "d.ts")) {
                val old = features[base + name]!!
                features[base + name] = HlsResolutionAdFilter.VideoInfo(width, height, old.ptsSeconds)
            }
            assertEquals(4, filter(p).removedSegments)
        }
    }

    @Test fun tenMinuteThresholdIsStrictAndLongCandidatesUseBoundedDistributedSamples() {
        for (segments in listOf(16, 299, 300, 301)) {
            playlist()
            val p = buildString {
                append("#EXTM3U\n#EXT-X-TARGETDURATION:60\n#EXTINF:60,\nbefore.ts\n")
                append("#EXT-X-DISCONTINUITY\n")
                for (i in 0 until segments) {
                    append("#EXTINF:2,\nlong$i.ts\n")
                    features[base + "long$i.ts"] = HlsResolutionAdFilter.VideoInfo(640, 360, 1.48 + i * 2)
                }
                append("#EXT-X-DISCONTINUITY\n#EXTINF:60,\nafter.ts\n#EXT-X-ENDLIST\n")
            }
            val calls = mutableListOf<String>()
            val result = HlsResolutionAdFilter.filter(base, p, { url -> calls.add(url); features[url] }) {
                b, r -> URI.create(b).resolve(r).toString()
            }
            if (segments < 300) {
                assertEquals(segments, result.removedSegments)
                assertEquals(18, calls.size)
                assertTrue(calls.contains(base + "long${segments - 1}.ts"))
                assertTrue(calls.any { it.endsWith("long${(segments - 1) / 15}.ts") })
                features[base + "long${segments - 1}.ts"] = HlsResolutionAdFilter.VideoInfo(640, 360, 9999.0)
                assertEquals(p, filter(p).content)
            } else {
                assertEquals(p, result.content)
                assertTrue(calls.isEmpty())
            }
        }
    }

    @Test fun proposed800By600MinimumIsNotEnforced() {
        val p = playlist()
        features.replaceAll { url, f ->
            val programme = url.endsWith("before.ts") || url.endsWith("after.ts")
            HlsResolutionAdFilter.VideoInfo(if (programme) 640 else 720, if (programme) 480 else 576, f.ptsSeconds)
        }
        assertEquals(4, filter(p).removedSegments)
    }

    @Test fun invalidProbeValuesNeverConfirmRemoval() {
        val p = playlist()
        for (invalid in arrayOf(
            HlsResolutionAdFilter.VideoInfo(0, 1080, 1.48),
            HlsResolutionAdFilter.VideoInfo(1920, -1, 1.48),
            HlsResolutionAdFilter.VideoInfo(1920, 1080, Double.NaN),
            HlsResolutionAdFilter.VideoInfo(1920, 1080, Double.POSITIVE_INFINITY),
            HlsResolutionAdFilter.VideoInfo(1920, 1080, -1.0))) {
            features[base + "a.ts"] = invalid
            assertEquals(p, filter(p).content)
        }
    }

    @Test fun programmeReturnAllowsRoundingButRejectsTimelineMismatch() {
        val p = playlist()
        features[base + "after.ts"] = HlsResolutionAdFilter.VideoInfo(1280, 720, 160.5)
        assertEquals(4, filter(p).removedSegments)
        features[base + "after.ts"] = HlsResolutionAdFilter.VideoInfo(1280, 720, 160.501)
        assertEquals(p, filter(p).content)
    }

    @Test fun lateProbeFailureKeepsTheWholeCandidateRatherThanPartiallyRemovingIt() {
        val p = playlist()
        val calls = AtomicInteger()
        val result = HlsResolutionAdFilter.filter(base, p, { url ->
            calls.incrementAndGet()
            if (url.endsWith("d.ts")) throw java.io.IOException("probe budget exhausted")
            features[url]
        }) { b, reference -> URI.create(b).resolve(reference).toString() }
        assertEquals(6, calls.get())
        assertEquals(p, result.content)
        assertEquals(0, result.removedBlocks)
        assertEquals(0, result.removedSegments)
    }

    @Test fun requiresBothLongProgrammeNeighboursBeforeProbing() {
        val p = playlist()
        val calls = AtomicInteger()
        for (input in arrayOf(p.replace("#EXTINF:60,\nbefore.ts", "#EXTINF:59.99,\nbefore.ts"),
            p.replace("#EXTINF:60,\nafter.ts", "#EXTINF:59.99,\nafter.ts"))) {
            val result = HlsResolutionAdFilter.filter(base, input, { url -> calls.incrementAndGet(); features[url] }
            ) { b, reference -> URI.create(b).resolve(reference).toString() }
            assertEquals(input, result.content)
        }
        assertEquals(0, calls.get())
    }

    @Test fun supportsCrLfAndPreservesDiscontinuitySequence() {
        val p = playlist().replace("#EXTM3U\n", "#EXTM3U\n#EXT-X-DISCONTINUITY-SEQUENCE:7\n").replace("\n", "\r\n")
        val result = filter(p)
        assertEquals(4, result.removedSegments)
        assertTrue(result.content.contains("#EXT-X-DISCONTINUITY-SEQUENCE:7"))
        assertTrue(result.content.contains("#EXT-X-ENDLIST"))
        assertEquals(2, result.content.lines().filter { it == "#EXT-X-DISCONTINUITY" }.count())
    }
}
