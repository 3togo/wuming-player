package com.wuming.tv.core.live

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Test

class LivePlaylistEncodingTest {
    private val playlist = "#EXTM3U\n#EXTINF:-1,中文台\nhttps://media.example/live.m3u8\n"

    @Test
    fun decodesMarkedUnicodeAndPlainUtf8() {
        val encodings = listOf(
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) to StandardCharsets.UTF_8,
            byteArrayOf(0xFE.toByte(), 0xFF.toByte()) to StandardCharsets.UTF_16BE,
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) to StandardCharsets.UTF_16LE,
            byteArrayOf(0, 0, 0xFE.toByte(), 0xFF.toByte()) to Charset.forName("UTF-32BE"),
            byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0) to Charset.forName("UTF-32LE"),
            byteArrayOf() to StandardCharsets.UTF_8,
        )
        for ((mark, charset) in encodings) {
            val decoded = LivePlaylistEncoding.decode(mark + playlist.toByteArray(charset))
            assertEquals(charset.name(), playlist, decoded)
            assertEquals("中文台", LivePlaylistParser.parse(decoded).groups.single().channels.single().name)
        }
    }

    @Test
    fun preservesShortUnmarkedInput() {
        assertEquals("#", LivePlaylistEncoding.decode(byteArrayOf('#'.code.toByte())))
    }
}
