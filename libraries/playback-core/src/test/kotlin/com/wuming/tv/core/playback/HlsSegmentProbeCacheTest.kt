package com.wuming.tv.core.playback

import java.io.IOException
import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class HlsSegmentProbeCacheTest {
    @Test fun sharesRealDetectorReadsWithoutChangingRemovalDecisions() {
        val base = "https://media.example/"
        val features = mapOf(
            "before.ts" to 100.0, "a.ts" to 1.48, "b.ts" to 8.44,
            "c.ts" to 12.44, "d.ts" to 15.0, "after.ts" to 160.0)
        val input = "#EXTM3U\n#EXT-X-TARGETDURATION:60\n#EXTINF:60,\nbefore.ts\n" +
            "#EXT-X-DISCONTINUITY\n#EXTINF:6.96,\na.ts\n#EXTINF:4,\nb.ts\n" +
            "#EXTINF:2.56,\nc.ts\n#EXTINF:3.84,\nd.ts\n" +
            "#EXT-X-DISCONTINUITY\n#EXTINF:60,\nafter.ts\n#EXT-X-ENDLIST\n"
        fun run(shared: Boolean): Pair<HlsResolutionAdFilter.Result, List<String>> {
            val calls = mutableListOf<String>()
            val delegate = HlsResolutionAdFilter.Probe { url ->
                calls.add(url)
                features[URI.create(url).path.substringAfterLast('/')]?.let {
                    HlsResolutionAdFilter.VideoInfo(1920, 1080, it)
                }
            }
            val probe = if (shared) HlsSegmentProbeCache(delegate) else delegate
            val resolution = HlsResolutionAdFilter.filter(base, input, probe) { b, r -> URI.create(b).resolve(r).toString() }
            assertEquals(0, resolution.removedSegments)
            val insertion = HlsTimestampInsertionFilter.filter(base, resolution.content, probe) { b, r -> URI.create(b).resolve(r).toString() }
            return insertion to calls
        }
        val (uncached, duplicateCalls) = run(false)
        val (cached, uniqueCalls) = run(true)
        assertEquals(4, cached.removedSegments)
        assertEquals(uncached.content, cached.content)
        assertEquals(9, duplicateCalls.size)
        assertEquals(6, uniqueCalls.size)
        assertEquals(uniqueCalls.size, uniqueCalls.toSet().size)
    }

    @Test fun cachesUnavailableInvalidAndFailedProbes() {
        for (kind in listOf("missing", "invalid", "failure")) {
            var calls = 0
            val failure = IOException("probe unavailable")
            val cache = HlsSegmentProbeCache {
                calls++
                when (kind) {
                    "failure" -> throw failure
                    "invalid" -> HlsResolutionAdFilter.VideoInfo(0, 1080, 1.48)
                    else -> null
                }
            }
            repeat(2) {
                if (kind == "failure") {
                    assertSame(failure, assertThrows(IOException::class.java) { cache.read("https://media.example/a.ts") })
                } else assertNull(cache.read("https://media.example/a.ts"))
            }
            assertEquals(1, calls)
        }
    }

    @Test fun queryIdentityAndPreparationLifetimeStaySeparate() {
        var calls = 0
        val delegate = HlsResolutionAdFilter.Probe {
            calls++
            HlsResolutionAdFilter.VideoInfo(1920, 1080, calls.toDouble())
        }
        val firstPreparation = HlsSegmentProbeCache(delegate)
        val first = firstPreparation.read("https://media.example/a.ts?version=1")
        assertSame(first, firstPreparation.read("https://media.example/a.ts?version=1"))
        assertNotSame(first, firstPreparation.read("https://media.example/a.ts?version=2"))
        val nextPreparation = HlsSegmentProbeCache(delegate)
        assertNotSame(first, nextPreparation.read("https://media.example/a.ts?version=1"))
        assertEquals(3, calls)
    }
}
