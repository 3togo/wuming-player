package com.wuming.tv.core.playback

import org.junit.Assert.*
import org.junit.Test

class MediaUrlClassifierTest {
    @Test fun `recognizes signed audio and video resources including short hosts`() {
        for (extension in listOf("mp3", "m4a", "m4b", "aac", "flac", "ogg", "oga", "opus", "wav", "mp4", "mkv")) {
            val url = "https://a/$extension.$extension?token=abc#chapter"
            assertTrue(url, MediaUrlClassifier.isMediaUrl(url))
            assertTrue(url, MediaUrlClassifier.isProgressiveUrl(url))
        }
        assertTrue(MediaUrlClassifier.isMediaUrl("HTTPS://MEDIA.EXAMPLE/BOOK.M4B"))
    }

    @Test fun `rejects web pages nested urls subtitles and partial extensions`() {
        for (url in listOf(
            "https://site.example/watch?url=https://media.example/file.mp3",
            "https://site.example/watch.html#file.mp4", "https://site.example/file.mp4.exe",
            "https://site.example/video/tos/watch.html",
            "https://site.example/title.srt", "file:///file.mp3", "not a URL", "https:///file.mp4",
        )) assertFalse(url, MediaUrlClassifier.isMediaUrl(url))
        assertFalse(MediaUrlClassifier.isMediaUrl(null))
    }

    @Test fun `manifests and streaming protocols are not progressive files`() {
        for (url in listOf("https://a/live.m3u8", "https://a/live.mpd", "rtmp://a/live", "https://a/video/tos/resource")) {
            assertTrue(url, MediaUrlClassifier.isMediaUrl(url))
            assertFalse(url, MediaUrlClassifier.isProgressiveUrl(url))
        }
    }
}
