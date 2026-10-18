package com.wuming.android.tv.player.exo

import android.net.Uri
import androidx.media3.common.C
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wuming.tv.core.playback.VideoRenditionPolicy.Support
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Offline fixtures: rendition exclusion happens before any rendition or segment request. */
@RunWith(AndroidJUnit4::class)
class CapabilityManifestInstrumentedTest {
    private val uri = Uri.parse("https://fixture.invalid/master.m3u8")
    private val master = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Audio",URI="audio.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=15000000,RESOLUTION=3840x2160,FRAME-RATE=50,CODECS="hvc1.2.4.L153.B0,mp4a.40.2",VIDEO-RANGE=PQ,AUDIO="audio"
        uhd.m3u8?token=one
        #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080,FRAME-RATE=25,CODECS="avc1.640028,mp4a.40.2",VIDEO-RANGE=SDR,AUDIO="audio"
        sdr.m3u8?token=two
    """.trimIndent().toByteArray()

    @Test fun filtersHdrBeforeRenditionRequestsWhileRetainingSdrAndAudio() {
        val result = CapabilityManifestDataSource.filter(uri, master) { format ->
            if (format.width == 3840) {
                assertEquals(C.COLOR_TRANSFER_ST2084, format.colorInfo!!.colorTransfer)
                Support.UNSUPPORTED
            } else Support.SUPPORTED
        }.toString(Charsets.UTF_8)
        assertFalse(result.contains("uhd.m3u8"))
        assertFalse(result.contains("RESOLUTION=3840x2160"))
        assertTrue(result.contains("sdr.m3u8?token=two"))
        assertTrue(result.contains("URI=\"audio.m3u8\""))
    }

    @Test fun knownIncompatibilityProducesTerminalReasonWithoutFetchingVideo() {
        val error = assertThrows(UnsupportedVideoException::class.java) {
            CapabilityManifestDataSource.filter(uri, master) { Support.UNSUPPORTED }
        }
        assertEquals(UnsupportedVideoException.Reason.NO_COMPATIBLE_RENDITION, error.reason)
        assertTrue(UnsupportedVideoException.isCapabilityRejection(java.io.IOException(error)))
    }

    @Test fun unknownMetadataAndExceedingOnlyFallbackRemainAvailable() {
        assertSame(master, CapabilityManifestDataSource.filter(uri, master) { Support.UNKNOWN })
        assertSame(master, CapabilityManifestDataSource.filter(uri, master) { Support.EXCEEDS })
        val media = "#EXTM3U\n#EXTINF:4,\nsegment.ts\n".toByteArray()
        assertSame(media, CapabilityManifestDataSource.filter(uri, media) {
            throw AssertionError("A media playlist has no advertised variant to assess")
        })
    }
}
