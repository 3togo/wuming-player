package com.wuming.android.tv.media.hls

import com.wuming.tv.core.playback.HlsAdFilterEngine
import com.wuming.tv.core.playback.HlsSegmentProbeSession
import com.wuming.tv.core.playback.fixtures.PostrollEpisodeFixture
import java.net.URI
import org.junit.Assert.*
import org.junit.Test

/** Runs in every Android mode/ABI unit suite, through Android's TS probe wrapper
 * and the same engine used by M3u8 and PlaybackActivity. The URI port uses Java
 * here because Android TextUtils in the production resolver needs a device. */
class HlsAppendedAdAdapterTest {
    @Test fun capturedEpisodeDropsOnlyThePostrollAndKeepsActualEndingCredits() {
        val fixture = PostrollEpisodeFixture()
        for (input in listOf(fixture.playlist, fixture.creditsOnly)) {
            val calls = mutableListOf<String>()
            val result = HlsAdFilterEngine.purify("https://media.example/", "https://media.example/", input,
                emptyList(), { url ->
                    calls.add(url)
                    TsVideoProbe.read(fixture.prefix(url))
                }) { b, r -> URI(b).resolve(r).toString() }
            assertEquals(fixture.urls.take(713), result.content.lines().filter { it.startsWith("https://") })
            assertTrue(result.content.trimEnd().endsWith("#EXT-X-ENDLIST"))
            assertEquals(if (input == fixture.playlist) 4 else 0, result.appended.removedSegments)
            assertEquals(if (input == fixture.playlist) 7 else 0, calls.size)
            assertEquals(calls.size, calls.toSet().size)
        }
    }

    @Test fun androidBoundedPreparationReceivesTimingMetadataFromTheSharedDecoder() {
        val fixture = PostrollEpisodeFixture()
        val session = HlsSegmentProbeSession(emptyMap(), { url, _, maxBytes, _ ->
            assertEquals(65536, maxBytes)
            HlsSegmentProbeSession.Prefix(200, null, fixture.prefix(url))
        })
        for (index in listOf(697, 712, 713, 716)) {
            val info = session.read(fixture.urls[index])!!
            assertEquals(if (index < 713) 25.0 else 30.0, info.nominalFrameRate, 0.001)
            assertEquals(fixture.info(index).ptsSeconds, info.ptsSeconds, 0.00002)
        }
    }
}
