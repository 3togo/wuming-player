package com.wuming.tv.desktop.live

import com.wuming.tv.core.live.LivePlaylistParser
import com.wuming.tv.core.live.LivePlaylistReference
import com.wuming.tv.desktop.config.DesktopTvBoxConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DesktopLiveSourceSmokeTest {

    private val configuration = DesktopTvBoxConfiguration(
        location = "config",
        resolvedLocation = "config",
        sites = emptyList(),
        lives = listOf(
            LivePlaylistReference("First", "first"),
            LivePlaylistReference("Second", "second"),
        ),
        spiderConfigured = false,
    )

    @Test
    fun `tests first configured playlist by default and supports an exact name`() {
        val loaded = mutableListOf<String>()
        val smoke = DesktopLiveSourceSmoke(
            loadConfiguration = { configuration },
            loadPlaylist = { location ->
                loaded += location
                LivePlaylistParser.parse("News,#genre#\n$location,https://example.test/$location.m3u8")
            },
            hasReachableStream = { true },
        )

        assertEquals("First", smoke.verify("config").reference.name)
        assertEquals("Second", smoke.verify("config", "Second").reference.name)
        assertEquals(listOf("first", "second"), loaded)
    }

    @Test
    fun `default probe skips an incompatible advertised live protocol`() {
        val loaded = mutableListOf<String>()
        val smoke = DesktopLiveSourceSmoke(
            loadConfiguration = { configuration },
            loadPlaylist = { location ->
                loaded += location
                if (location == "first") error("not a portable playlist")
                LivePlaylistParser.parse("News,#genre#\nSecond,https://example.test/live.m3u8")
            },
            hasReachableStream = { true },
        )

        val report = smoke.verify("config")

        assertEquals("Second", report.reference.name)
        assertEquals(listOf("First"), report.skippedSources)
        assertEquals(listOf("first", "second"), loaded)
    }

    @Test
    fun `rejects a requested playlist that configuration does not advertise`() {
        val smoke = DesktopLiveSourceSmoke(loadConfiguration = { configuration }, hasReachableStream = { true })

        assertThrows(IllegalArgumentException::class.java) { smoke.verify("config", "Missing") }
    }

    @Test
    fun `default probe skips a parsed playlist whose streams are unreachable`() {
        val smoke = DesktopLiveSourceSmoke(
            loadConfiguration = { configuration },
            loadPlaylist = { location ->
                LivePlaylistParser.parse("News,#genre#\n$location,https://example.test/$location.m3u8")
            },
            hasReachableStream = { playlist ->
                playlist.groups.single().channels.single().name == "second"
            },
        )

        val report = smoke.verify("config")

        assertEquals("Second", report.reference.name)
        assertEquals(listOf("First"), report.skippedSources)
    }
}
