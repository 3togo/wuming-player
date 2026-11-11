package com.wuming.tv.desktop.ui

import com.wuming.tv.desktop.config.DesktopMpvConfiguration
import java.awt.Container
import java.awt.Window
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.prefs.Preferences
import javax.swing.*
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test

class MpvConfigurationEditorIntegrationTest {
    @Test fun cancelKeepsThePublishedConfigurationAndAcceptPublishesTheStagedText() {
        assumeFalse(java.awt.GraphicsEnvironment.isHeadless())
        val dir = Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("user.home"), "temp")), "mpv-editor-")
        val preferences = Preferences.userRoot().node("wuming-mpv-editor/${System.nanoTime()}")
        val config = DesktopMpvConfiguration(dir.resolve("mpv.conf"))
        config.save("deband=no")
        val failure = AtomicReference<Throwable>()
        fun descendants(root: Container): List<java.awt.Component> = root.components.flatMap { listOf(it) + if (it is Container) descendants(it) else emptyList() }
        try {
            for (accept in listOf(false, true)) {
                val done = CountDownLatch(1)
                var timer: Timer? = null
                SwingUtilities.invokeAndWait {
                    val panel = PlaybackPreferencesPanel(preferences, config)
                    timer = Timer(100) {
                        val dialog = Window.getWindows().filterIsInstance<JDialog>().firstOrNull { it.isShowing && it.title == "mpv configuration" } ?: return@Timer
                        try {
                            val editor = descendants(dialog).filterIsInstance<JTextArea>().single()
                            assertEquals("deband=no", editor.text)
                            editor.text = "deband=yes\nno-border"
                            assertEquals("deband=no", config.read())
                            assertTrue(descendants(dialog).filterIsInstance<JLabel>().any { it.text?.contains("border") == true })
                            descendants(dialog).filterIsInstance<JOptionPane>().single().value = if (accept) JOptionPane.OK_OPTION else JOptionPane.CANCEL_OPTION
                        } catch (error: Throwable) { failure.set(error); dialog.dispose() }
                        finally { timer?.stop() }
                    }.apply { start() }
                    SwingUtilities.invokeLater {
                        try { descendants(panel).filterIsInstance<JButton>().single { it.text == "Edit…" }.doClick() }
                        catch (error: Throwable) { failure.set(error) }
                        finally { done.countDown() }
                    }
                }
                try { assertTrue("Editor did not close", done.await(5, TimeUnit.SECONDS)); failure.get()?.let { throw it } }
                finally { SwingUtilities.invokeAndWait { timer?.stop(); Window.getWindows().filterIsInstance<JDialog>().filter { it.title == "mpv configuration" }.forEach { it.dispose() } } }
                assertEquals(if (accept) "deband=yes\nno-border" else "deband=no", config.read())
            }
        } finally { preferences.removeNode(); dir.toFile().deleteRecursively() }
    }
}
