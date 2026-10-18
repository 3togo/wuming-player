package com.wuming.tv.core.source

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemovedSdkProtocolsTest {
    @Test fun nativeLinksAreRejectedRegardlessOfSpelling() {
        listOf("P2p://host/item", "p3p://host/item", "P9P://host/item", "mitv://host/item",
            "JIANPIAN://pathtype=url&path=example", "TVBOX-XG://host/item", "xgplay://host/item",
            "TVBUS://host/item", "ftp://example.test/movie.mkv", "MAGNET:?xt=urn:btih:example",
            "ed2k://|file|item", "THUNDER://example", "https://example.test/item.TORRENT?download=1",
            "/storage/item.torrent").forEach { assertTrue(it, RemovedSdkProtocols.matches(it)) }
    }
    @Test fun ordinaryMediaAndProtocolWordsInsideHttpQueriesRemainUsable() {
        listOf(null, "", "https://example.test/movie.mp4?title=thunder", "https://example.test/live.m3u8",
            "https://example.test/a.mpd", "http://example.test/search?q=magnet:example",
            "rtsp://example.test/live", "rtmp://example.test/live", "file:///movie.mp4",
            "content://media/item", "video://example").forEach { assertFalse(it, RemovedSdkProtocols.matches(it)) }
    }
}
