package com.wuming.tv.desktop.ui

import com.wuming.tv.desktop.player.ExternalPlayerAdapter
import com.wuming.tv.desktop.player.PlaybackState
import java.nio.file.Path
import java.util.prefs.Preferences
import javax.swing.SwingUtilities
import org.junit.Assert.*
import org.junit.Test

class EmbeddedPlaybackPanelTest {
    @Test fun surfaceFailureAndUserPreferenceFallBackWithoutCreatingAnInvalidNativeTarget() {
        val prefs = Preferences.userRoot().node("wuming-embedded-test/${System.nanoTime()}")
        val player = ExternalPlayerAdapter(Path.of("/usr/bin/mpv"))
        var attempts = 0
        lateinit var panel: EmbeddedPlaybackPanel
        try {
            SwingUtilities.invokeAndWait {
                panel = EmbeddedPlaybackPanel(player, prefs, nativeWindow = { attempts++; 0 }, supported = { true })
            }
            assertNull(panel.acquire("Example", false))
            assertEquals(1, attempts)
            prefs.putBoolean("playback.embedded", false)
            assertNull(panel.acquire("Example", false))
            assertEquals(1, attempts)
            SwingUtilities.invokeAndWait { assertFalse(panel.isVisible); panel.close() }
        } finally { prefs.removeNode() }
    }

    @Test fun fullscreenStateAndPauseDisplayFollowHostStateAndCleanup() {
        val prefs = Preferences.userRoot().node("wuming-embedded-test/${System.nanoTime()}")
        val changes = mutableListOf<Boolean>()
        try {
            SwingUtilities.invokeAndWait {
                val panel = EmbeddedPlaybackPanel(ExternalPlayerAdapter(Path.of("/usr/bin/mpv")), prefs,
                    fullscreenChanged = { changes += it }, nativeWindow = { 123L }, supported = { true })
                assertEquals(123L, panel.acquire("Example", true))
                assertTrue(panel.fullscreenActive)
                assertEquals("Only video belongs in fullscreen", listOf(panel.surface), panel.components.filter { it.isVisible })
                assertEquals(0, panel.insets.top + panel.insets.bottom + panel.insets.left + panel.insets.right)
                panel.setFullscreen(false)
                assertEquals(3, panel.components.count { it.isVisible })
                panel.setFullscreen(true)
                panel.acquire("Next episode", false)
                assertTrue(panel.fullscreenActive)
                panel.updateState(PlaybackState(positionSeconds = 12.0, durationSeconds = 30.0, paused = true))
                panel.playbackClosed()
                assertFalse(panel.fullscreenActive)
                assertFalse(panel.isVisible)
                assertEquals(listOf(true, false, true, false), changes)
                panel.close()
            }
        } finally { prefs.removeNode() }
    }
}
