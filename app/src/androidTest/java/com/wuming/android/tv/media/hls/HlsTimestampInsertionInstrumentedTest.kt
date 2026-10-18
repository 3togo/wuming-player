package com.wuming.android.tv.media.hls

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wuming.tv.core.playback.HlsTimestampInsertionFilter
import java.io.File
import java.net.URI
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HlsTimestampInsertionInstrumentedTest {
    @Test fun androidPreparationProbesEachSegmentOnceAcrossBothDetectors() {
        val features = mapOf("before.ts" to 100.0, "a.ts" to 1.48, "b.ts" to 8.44,
            "c.ts" to 12.44, "d.ts" to 15.0, "after.ts" to 160.0)
        val playlist = "#EXTM3U\n#EXT-X-TARGETDURATION:60\n#EXTINF:60,\nbefore.ts\n" +
            "#EXT-X-DISCONTINUITY\n#EXTINF:6.96,\na.ts\n#EXTINF:4,\nb.ts\n" +
            "#EXTINF:2.56,\nc.ts\n#EXTINF:3.84,\nd.ts\n" +
            "#EXT-X-DISCONTINUITY\n#EXTINF:60,\nafter.ts\n#EXT-X-ENDLIST\n"
        val calls = mutableListOf<String>()
        repeat(2) {
            val result = com.wuming.android.tv.utils.M3u8.purify(
                "https://media.example/", "https://media.example/", playlist) { url ->
                calls.add(url)
                features[URI.create(url).path.substringAfterLast('/')]?.let { pts ->
                    com.wuming.tv.core.playback.HlsResolutionAdFilter.VideoInfo(1920, 1080, pts)
                }
            }
            assertNotNull(result)
            assertEquals(listOf("https://media.example/before.ts", "https://media.example/after.ts"),
                result!!.lines().filter { it.startsWith("http") })
            val preparationCalls = calls.drop(it * 6)
            assertEquals(6, preparationCalls.size)
            assertEquals(6, preparationCalls.toSet().size)
        }
        assertEquals(12, calls.size)
    }

    @Test fun capturedPlaylistRemovesOnlyConfirmedInsertionWithRealTsPrefixes() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Optional captured HLS fixture", args.getString("hlsAdFixture") == "true")
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "hls-ad-fixture")
        val playlist = File(root, "media.m3u8").readText()
        val calls = mutableListOf<String>()
        val result = HlsTimestampInsertionFilter.filter("https://media.example/", playlist, { url ->
            calls.add(url)
            val file = File(root, URI.create(url).path.substringAfterLast('/'))
            if (file.exists()) TsVideoProbe.read(file.readBytes()) else null
        }) { base, reference -> URI.create(base).resolve(reference).toString() }
        assertEquals(1, result.removedBlocks)
        assertEquals(15, result.removedSegments)
        assertEquals(17, calls.size)
        val original = playlist.lines().filter { it.startsWith("http") }
        val adUrls = File(root, "ad-urls.txt").readLines().toSet()
        val retained = result.content.lines().filter { it.startsWith("http") }
        assertEquals(original.filterNot { it in adUrls }, retained)
        assertEquals(15, original.size - retained.size)
        assertTrue(result.content.contains("#EXT-X-ENDLIST"))
        val prepared = com.wuming.android.tv.utils.M3u8.purify(
            "https://v8.zuidazym3u8.com/yyv8/202311/02/96eiptSihd1/video/2000k_1080/hls/",
            "https://v8.zuidazym3u8.com/", playlist) { url ->
            val file = File(root, URI.create(url).path.substringAfterLast('/'))
            if (file.exists()) TsVideoProbe.read(file.readBytes()) else null
        }
        assertNotNull(prepared)
        assertEquals(retained, prepared!!.lines().filter { it.startsWith("http") })
    }
}
