package com.wuming.tv.core.playback

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class HlsTimestampInsertionFilterTest {
    private val base = "https://media.example/"
    private val info = mutableMapOf<String, HlsResolutionAdFilter.VideoInfo>()

    private fun fixture(): String {
        info.clear()
        return buildString {
            append("#EXTM3U\n#EXT-X-TARGETDURATION:2\n")
            // The measured source has many short programme discontinuities.
            for (i in 0 until 735) {
                if (i % 10 == 0 || i == 734) append("#EXT-X-DISCONTINUITY\n")
                append("#EXTINF:2,\np$i.ts\n")
                info[base + "p$i.ts"] = HlsResolutionAdFilter.VideoInfo(1920, 1080, i * 2 + 1.48)
            }
            append("#EXT-X-DISCONTINUITY\n")
            for (i in 0 until 15) {
                append("#EXTINF:${if (i == 14) 1.28 else 2.0},\na$i.ts\n")
                info[base + "a$i.ts"] = HlsResolutionAdFilter.VideoInfo(1920, 1080, i * 2 + 1.48)
            }
            append("#EXT-X-DISCONTINUITY\n#EXT-X-DISCONTINUITY\n")
            for (i in 735 until 775) {
                if (i % 10 == 0) append("#EXT-X-DISCONTINUITY\n")
                append("#EXTINF:2,\np$i.ts\n")
                info[base + "p$i.ts"] = HlsResolutionAdFilter.VideoInfo(1920, 1080, i * 2 + 1.48)
            }
            append("#EXT-X-ENDLIST\n")
        }
    }

    private fun filter(p: String, probe: HlsResolutionAdFilter.Probe = HlsResolutionAdFilter.Probe { info[it] }) =
        HlsTimestampInsertionFilter.filter(base, p, probe) { b, r -> URI.create(b).resolve(r).toString() }

    @Test fun removesMeasuredSameResolutionInsertionDespiteShortProgrammeBlocks() {
        val p = fixture()
        assertEquals(0, HlsResolutionAdFilter.filter(base, p, { info[it] }) { b, r ->
            URI.create(b).resolve(r).toString()
        }.removedSegments)
        val calls = mutableListOf<String>()
        val result = filter(p) { calls.add(it); info[it] }
        assertEquals(1, result.removedBlocks)
        assertEquals(15, result.removedSegments)
        assertEquals(17, calls.size)
        assertEquals((0 until 775).map { base + "p$it.ts" },
            result.content.lines().filter { it.isNotBlank() && !it.startsWith("#") })
        assertEquals(0, filter(result.content).removedSegments)
    }

    @Test fun normalTimestampContinuityNeverConfirmsAnInsertion() {
        val p = fixture()
        for (i in 0 until 15) info[base + "a$i.ts"] =
            HlsResolutionAdFilter.VideoInfo(1920, 1080, 1471.48 + i * 2)
        assertEquals(p, filter(p).content)
    }

    @Test fun timestampResetWithoutProgrammeResumptionIsRetained() {
        val p = fixture()
        info[base + "p735.ts"] = HlsResolutionAdFilter.VideoInfo(1920, 1080, 1500.76)
        assertEquals(p, filter(p).content)
    }

    @Test fun everyCandidateSegmentMustConfirmFormatAndInternalTimeline() {
        for (failure in listOf(null,
            HlsResolutionAdFilter.VideoInfo(1280, 720, 15.48),
            HlsResolutionAdFilter.VideoInfo(1920, 1080, 99.0))) {
            val p = fixture()
            if (failure == null) info.remove(base + "a7.ts") else info[base + "a7.ts"] = failure
            assertEquals(p, filter(p).content)
        }
        val p = fixture()
        assertEquals(p, filter(p) { if (it.endsWith("a14.ts")) error("budget exhausted") else info[it] }.content)
    }

    @Test fun requiresTruncatedTailAndSupportedVodBeforeSpendingProbeBudget() {
        val p = fixture()
        for (input in listOf(p.replace("1.28", "2.0"), p.replace("#EXT-X-ENDLIST", ""),
            p.replace("#EXTM3U", "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\""))) {
            var calls = 0
            assertEquals(input, filter(input) { calls++; info[it] }.content)
            assertEquals(0, calls)
        }
    }
}
