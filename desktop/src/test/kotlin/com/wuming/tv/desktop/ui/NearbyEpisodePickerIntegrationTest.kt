package com.wuming.tv.desktop.ui

import com.wuming.tv.core.catalog.*
import com.wuming.tv.desktop.library.DesktopUserLibrary
import java.awt.Container
import java.awt.KeyboardFocusManager
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

class NearbyEpisodePickerIntegrationTest {
    @Test fun keyboardPickerFocusesTheCurrentEpisodeAndMapsAcrossTheOldBoundary() = exercise(false)
    @Test fun closingTheCatalogueInvalidatesAnOpenPicker() = exercise(true)

    private fun exercise(closeBeforeChoose: Boolean) {
        assumeFalse(java.awt.GraphicsEnvironment.isHeadless())
        val dir = Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("user.home"), "temp")), "nearby-picker-")
        val preferences = Preferences.userRoot().node("wuming-nearby-test/${System.nanoTime()}")
        val failure = AtomicReference<Throwable>()
        val done = CountDownLatch(1)
        var panel: CatalogPanel? = null
        var frame: JFrame? = null
        var timer: Timer? = null
        fun descendants(root: Container): List<java.awt.Component> = root.components.flatMap { listOf(it) + if (it is Container) descendants(it) else emptyList() }
        try {
            SwingUtilities.invokeAndWait {
                panel = CatalogPanel(null, preferences, userLibrary = DesktopUserLibrary.open(dir.resolve("library.json")))
                val target = panel!!
                fun field(name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
                val line = CatalogLine("direct", "Direct", (0 until 400).map { CatalogEpisode("Episode ${it + 1}", "id-$it") })
                field("detail").set(target, CatalogDetail(lines = listOf(line)))
                val lines = field("lines").get(target) as JComboBox<CatalogLine>
                lines.model = DefaultComboBoxModel(arrayOf(line))
                val episodes = field("episodes").get(target) as JList<String>
                episodes.model = DefaultListModel<String>().apply { line.episodes.forEach { addElement(it.name) } }
                episodes.selectedIndex = 120
                frame = JFrame("Fixture").apply { add(target); setSize(900, 700); isVisible = true }
                timer = Timer(200) {
                    val dialog = Window.getWindows().filterIsInstance<JDialog>().firstOrNull { it.isShowing && it.title == "Nearby episodes" } ?: return@Timer
                    try {
                        val picker = descendants(dialog).filterIsInstance<JList<*>>().single()
                        if (KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner !== picker) return@Timer
                        assertEquals(121, picker.model.size)
                        assertEquals("Episode 121", picker.selectedValue)
                        assertEquals("Episode 61", picker.model.getElementAt(0))
                        if (closeBeforeChoose) target.close()
                        picker.selectedIndex = 59
                        picker.actionMap.get("choose").actionPerformed(java.awt.event.ActionEvent(picker, 0, "choose"))
                        assertEquals(if (closeBeforeChoose) 120 else 119, episodes.selectedIndex)
                        assertEquals(if (closeBeforeChoose) "Episode 121" else "Episode 120", episodes.selectedValue)
                    } catch (error: Throwable) { failure.set(error) }
                    finally { dialog.dispose(); timer?.stop(); done.countDown() }
                }.apply { start() }
                SwingUtilities.invokeLater {
                    try { target.javaClass.getDeclaredMethod("showNearbyEpisodes").apply { isAccessible = true }.invoke(target) }
                    catch (error: Throwable) { failure.set(error); done.countDown() }
                }
            }
            assertTrue("Picker failed to acquire keyboard focus", done.await(10, TimeUnit.SECONDS))
            failure.get()?.let { throw it }
        } finally {
            SwingUtilities.invokeAndWait {
                timer?.stop()
                Window.getWindows().filterIsInstance<JDialog>().filter { it.title == "Nearby episodes" }.forEach { it.dispose() }
                panel?.close(); frame?.dispose()
            }
            preferences.removeNode()
            dir.toFile().deleteRecursively()
        }
    }
}
