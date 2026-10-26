package com.wuming.tv.desktop.live

import java.nio.file.Files
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopLivePlaylistLoaderTest {

    @Test
    fun loadsJsonPlaylistThroughTheSharedGrammar() {
        val loader = DesktopLivePlaylistLoader {
            """[{"group":"Radio","channels":[{"name":"One","url":"https://media.example/radio.flac","ua":"Radio"}]}]""".toByteArray()
        }
        val playlist = loader.load("https://playlist.example/radio.json")
        assertEquals("Radio", playlist.groups.single().name)
        assertEquals("Radio", playlist.groups.single().channels.single().streams.single().headers["User-Agent"])
    }

    @Test
    fun loadsUtf16PlaylistFromLocalFile() {
        val file = Files.createTempFile("tvbox-live-utf16-", ".m3u")
        val content = "#EXTM3U\n#EXTINF:-1,中文台\nhttps://media.example/live.m3u8\n"
        Files.write(file, byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + content.toByteArray(StandardCharsets.UTF_16LE))
        try {
            assertEquals("中文台", DesktopLivePlaylistLoader().load(file.toString()).groups.single().channels.single().name)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun loadsLocalPlaylistPathAndFileUri() {
        val file = Files.createTempFile("tvbox-live-", ".m3u")
        Files.writeString(file, "#EXTM3U\n#EXTINF:-1 group-title=\"News\",CCTV-1\nhttps://media.example/live.m3u8\n")
        try {
            val loader = DesktopLivePlaylistLoader()

            assertEquals("CCTV-1", loader.load(file.toString()).groups.single().channels.single().name)
            assertEquals(1, loader.load(file.toUri().toString()).channelCount)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun rejectsUnsupportedLocationsAndOversizedFiles() {
        val file = Files.createTempFile("tvbox-live-large-", ".txt")
        Files.writeString(file, "12345")
        try {
            val loader = DesktopLivePlaylistLoader(maximumBytes = 4)

            assertThrows(IllegalArgumentException::class.java) { loader.load("ftp://example.com/live.m3u") }
            assertThrows(IllegalArgumentException::class.java) { loader.load(file.toString()) }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun prefersCanonicalUrlEmbeddedInMirrorPath() {
        val requested = mutableListOf<String>()
        val playlist = "#EXTM3U\n#EXTINF:-1 group-title=\"News\",CCTV2\nhttps://media.example/cctv2.m3u8\n"
        val mirror = "https://mirror.example/https://raw.githubusercontent.com/example/live/main/list.m3u?token=one"
        val loader = DesktopLivePlaylistLoader { location ->
            requested += location
            playlist.toByteArray()
        }

        assertEquals("CCTV2", loader.load(mirror).groups.single().channels.single().name)
        assertEquals(
            listOf("https://raw.githubusercontent.com/example/live/main/list.m3u?token=one"),
            requested,
        )
    }

    @Test
    fun retriesMirrorWhenCanonicalLocationFails() {
        val requested = mutableListOf<String>()
        val mirror = "https://mirror.example/https://raw.githubusercontent.com/example/live/main/list.m3u"
        val loader = DesktopLivePlaylistLoader { location ->
            requested += location
            if (location.startsWith("https://raw.githubusercontent.com")) throw IllegalStateException("direct unavailable")
            "#EXTM3U\n#EXTINF:-1,CCTV2\nhttps://media.example/cctv2.m3u8\n".toByteArray()
        }

        assertEquals(1, loader.load(mirror).channelCount)
        assertEquals(2, requested.size)
        assertTrue(requested.last().startsWith("https://mirror.example/"))
    }
}
