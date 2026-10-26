package com.wuming.tv.core.source

import org.junit.Assert.*
import org.junit.Test

class PlaybackProtocolPolicyTest {
    @Test fun publicBlocksNativeInputsWhilePrivatePreservesRouting() {
        for (url in listOf("tvbus://channel", "jianpian://item", "magnet:?xt=example", "p2p://channel", "https://example.test/movie.torrent")) {
            assertTrue(url, PlaybackProtocolPolicy.unsupported(url, false))
            assertFalse(url, PlaybackProtocolPolicy.unsupported(url, true))
        }
        for (url in listOf("https://example.test/movie.m3u8", "file:///movie.mp4", "rtsp://stream")) {
            assertFalse(url, PlaybackProtocolPolicy.unsupported(url, false))
            assertFalse(url, PlaybackProtocolPolicy.unsupported(url, true))
        }
    }
}
