package com.wuming.tv.desktop.config

import java.util.UUID
import java.util.prefs.Preferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DesktopPlaybackSettingsTest {
    private lateinit var preferences: Preferences

    @Before
    fun createPreferences() {
        preferences = Preferences.userRoot().node("tvbox-test/${UUID.randomUUID()}")
    }

    @After
    fun removePreferences() {
        preferences.removeNode()
    }

    @Test
    fun `auto-next defaults on and persists an explicit stop`() {
        val settings = DesktopPlaybackSettings(preferences)
        assertTrue(settings.autoPlayNextEpisode)

        settings.autoPlayNextEpisode = false

        assertFalse(DesktopPlaybackSettings(preferences).autoPlayNextEpisode)
    }

    @Test
    fun `autoPlayFirstEpisode defaults on and persists`() {
        val settings = DesktopPlaybackSettings(preferences)
        assertTrue(settings.autoPlayFirstEpisode)

        settings.autoPlayFirstEpisode = false

        assertFalse(DesktopPlaybackSettings(preferences).autoPlayFirstEpisode)
    }

    @Test
    fun `restorePlaybackPosition defaults on and persists`() {
        val settings = DesktopPlaybackSettings(preferences)
        assertTrue(settings.restorePlaybackPosition)

        settings.restorePlaybackPosition = false

        assertFalse(DesktopPlaybackSettings(preferences).restorePlaybackPosition)
    }

    @Test
    fun `defaultSpeed defaults to 1_0 and persists`() {
        val settings = DesktopPlaybackSettings(preferences)
        assertEquals(1.0, settings.defaultSpeed, 0.001)

        settings.defaultSpeed = 1.5

        assertEquals(1.5, DesktopPlaybackSettings(preferences).defaultSpeed, 0.001)
    }

    @Test
    fun `prewarmNextEpisode defaults on and persists`() {
        val settings = DesktopPlaybackSettings(preferences)
        assertTrue(settings.prewarmNextEpisode)

        settings.prewarmNextEpisode = false

        assertFalse(DesktopPlaybackSettings(preferences).prewarmNextEpisode)
    }

    @Test
    fun `fullscreenOnStart defaults off and persists`() {
        val settings = DesktopPlaybackSettings(preferences)
        assertFalse(settings.fullscreenOnStart)

        settings.fullscreenOnStart = true

        assertTrue(DesktopPlaybackSettings(preferences).fullscreenOnStart)
    }
}
