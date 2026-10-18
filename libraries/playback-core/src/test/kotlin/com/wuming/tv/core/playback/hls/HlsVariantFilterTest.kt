package com.wuming.tv.core.playback.hls

import org.junit.Assert.*
import org.junit.Test

class HlsVariantFilterTest {
    @Test fun preservesAudioKeysVariablesAndSignedCompatibleUris() {
        val playlist = """
            #EXTM3U
            #EXT-X-DEFINE:NAME="auth",VALUE="secret"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",URI="audio.m3u8"
            #EXT-X-SESSION-KEY:METHOD=AES-128,URI="key"
            #EXT-X-STREAM-INF:BANDWIDTH=15000000,RESOLUTION=3840x2160,VIDEO-RANGE=PQ
            # comment
            uhd.m3u8?token=one
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080,AUDIO="audio"
            sdr.m3u8?token=two
        """.trimIndent()
        val filtered = HlsVariantFilter.filter(playlist) { it.startsWith("sdr.") }
        assertFalse(filtered.contains("RESOLUTION=3840x2160"))
        assertFalse(filtered.contains("uhd.m3u8"))
        assertTrue(filtered.contains("sdr.m3u8?token=two"))
        assertTrue(filtered.contains("#EXT-X-MEDIA:"))
        assertTrue(filtered.contains("#EXT-X-SESSION-KEY:"))
        assertTrue(filtered.contains("#EXT-X-DEFINE:"))
    }

    @Test fun mediaPlaylistsAndRetainedMastersStayIdenticalIncludingCrLf() {
        val media = "#EXTM3U\r\n#EXTINF:4,\r\nsegment.ts\r\n"
        assertEquals(media, HlsVariantFilter.filter(media) { false })
        val master = "#EXTM3U\r\n#EXT-X-STREAM-INF:BANDWIDTH=1000\r\nlow.m3u8\r\n"
        assertEquals(master, HlsVariantFilter.filter(master) { true })
    }
}
