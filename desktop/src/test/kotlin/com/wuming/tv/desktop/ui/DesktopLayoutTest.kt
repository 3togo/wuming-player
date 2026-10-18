package com.wuming.tv.desktop.ui

import com.wuming.tv.core.catalog.*
import com.wuming.tv.core.media.TvHomeAction
import com.wuming.tv.desktop.config.DesktopPlaybackSettings
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage
import java.io.File
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import javax.swing.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Exercises real Swing ports with an offline catalogue, without a window server or live providers. */
class DesktopLayoutTest {
    @Test
    fun historyPrivacyControlsRemainReachableAfterSearchOrDetails() {
        assumeTrue("Skip Swing UI test in headless environment", !GraphicsEnvironment.isHeadless())
        val prefs = Preferences.userRoot().node("tvbox-history-layout/${System.nanoTime()}")
        val directory = java.nio.file.Files.createTempDirectory("history-layout")
        val library = com.wuming.tv.desktop.library.DesktopUserLibrary.open(directory.resolve("library.json"))
        try {
            SwingUtilities.invokeAndWait {
                val panel = CatalogPanel(null, prefs, userLibrary = library)
                try {
                    panel.focusSearch()
                    panel.showLibrary()
                    assertTrue(field<JPanel>(panel, "vodControls").isVisible)
                    assertTrue(field<JPanel>(panel, "historyActions").isVisible)
                    panel.javaClass.getDeclaredField("showingDetails").apply { isAccessible = true; setBoolean(panel, true) }
                    panel.showLibrary()
                    assertFalse(field<Boolean>(panel, "showingDetails"))
                    assertTrue(field<JButton>(panel, "clearHistory").isFocusable)
                    render(panel, 960, 620, "history-controls")
                    field<JCheckBox>(panel, "privateViewing").doClick()
                    assertTrue(DesktopPlaybackSettings(prefs).privateViewing)
                } finally { panel.close() }
            }
        } finally { prefs.removeNode(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun playbackPreferencesPersistAcrossPanelRecreation() {
        assumeTrue("Skip Swing UI test in headless environment", !GraphicsEnvironment.isHeadless())
        val prefs = Preferences.userRoot().node("tvbox-layout-test/${System.nanoTime()}")
        try {
            SwingUtilities.invokeAndWait {
                DesktopTvTheme.install()
                val panel = PlaybackPreferencesPanel(prefs)
                val toggles = descendants(panel).filterIsInstance<JCheckBox>()
                toggles.forEach { it.doClick() }
                descendants(panel).filterIsInstance<JComboBox<*>>().single().selectedItem = 1.5
                val settings = DesktopPlaybackSettings(prefs)
                assertFalse(settings.autoPlayFirstEpisode)
                assertFalse(settings.autoPlayNextEpisode)
                assertFalse(settings.restorePlaybackPosition)
                assertTrue(settings.fullscreenOnStart)
                assertEquals(1.5, settings.defaultSpeed, 0.0)
                val restored = PlaybackPreferencesPanel(prefs)
                assertEquals(toggles.map { it.isSelected }.toList(), descendants(restored).filterIsInstance<JCheckBox>().map { it.isSelected }.toList())
                render(restored, 760, 500, "playback-settings")
            }
        } finally { prefs.removeNode() }
    }

    @Test
    fun catalogueGivesSpaceToCardsAndRevealsOnlyRelevantControls() {
        assumeTrue("Skip Swing UI test in headless environment", !GraphicsEnvironment.isHeadless())
        val prefs = Preferences.userRoot().node("tvbox-layout-test/${System.nanoTime()}")
        DesktopPlaybackSettings(prefs).autoPlayFirstEpisode = false
        val executor = object : CatalogSourceExecutor {
            override val source = CatalogSource("offline-layout", "Cinema · offline preview")
            override fun browseHome() = CatalogHome(
                types = listOf(CatalogType("movies", "Movies"), CatalogType("series", "Series")),
                items = (1..20).map { CatalogItem(source.key, "$it", "A story worth watching $it", remarks = "2026 · Drama") },
            )
            override fun search(query: String) = emptyList<CatalogItem>()
            override fun detail(id: String) = CatalogDetail(
                lines = listOf(CatalogLine("main", "Main line", listOf(CatalogEpisode("Episode 1", "one")))),
            )
            override fun play(flag: String, id: String) = CatalogPlayback()
        }
        lateinit var panel: CatalogPanel
        SwingUtilities.invokeAndWait {
            DesktopTvTheme.install()
            panel = CatalogPanel(null, prefs)
            panel.addLocalExecutor(executor)
        }
        try {
            eventually { field<JList<*>>(panel, "results").model.size == 20 }
            SwingUtilities.invokeAndWait {
                val shell = JPanel(BorderLayout(16, 16)).apply {
                    border = BorderFactory.createEmptyBorder(20, 20, 20, 20)
                    add(JLabel("無名  /  Desktop").apply { font = font.deriveFont(22f) }, BorderLayout.NORTH)
                    add(TvHomeNavigationPanel(onSelected = {}).apply { showSelection(TvHomeAction.VOD) }, BorderLayout.WEST)
                    add(panel, BorderLayout.CENTER)
                }
                for ((width, height) in listOf(960 to 620, 1280 to 800)) {
                    render(shell, width, height, "catalogue-$width")
                    assertTrue("Catalogue keeps usable height: ${field<JList<*>>(panel, "results").parent.height} at $width×$height", field<JList<*>>(panel, "results").parent.height >= 240)
                    assertFalse(field<JPanel>(panel, "playerActions").isVisible)
                    assertFalse(field<JPanel>(panel, "episodeActions").isVisible)
                    assertFalse(field<JPanel>(panel, "auditActions").isVisible)
                    assertTrue(field<JComboBox<*>>(panel, "sources").width >= 300)
                }
                descendants(panel).filterIsInstance<JButton>().single { it.text == "Source tools" }.doClick()
                assertTrue(field<JPanel>(panel, "auditActions").isVisible)
                field<JList<*>>(panel, "results").selectedIndex = 0
                field<JButton>(panel, "loadDetails").doClick()
            }
            eventually { field<JPanel>(panel, "episodeActions").isVisible }
            SwingUtilities.invokeAndWait {
                assertFalse(field<JPanel>(panel, "playerActions").isVisible)
                render(panel, 760, 560, "catalogue-details")
            }
        } finally {
            SwingUtilities.invokeAndWait { panel.close() }
            prefs.removeNode()
        }
    }

    private fun render(component: JComponent, width: Int, height: Int, name: String) {
        val root = JRootPane()
        root.contentPane = component
        val host = JPanel(BorderLayout()).apply { add(root) }
        host.addNotify()
        root.setSize(width, height)
        root.doLayout()
        component.setSize(width, height)
        fun layout(container: Container) {
            container.doLayout()
            container.components.filterIsInstance<Container>().forEach(::layout)
        }
        repeat(3) { layout(root) }
        root.validate()
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().let { graphics -> component.printAll(graphics); graphics.dispose() }
        System.getenv("TVBOX_DESKTOP_RENDER_DIR")?.let { directory ->
            val file = File(directory, "$name.png")
            file.parentFile.mkdirs()
            ImageIO.write(image, "png", file)
        }
        host.removeNotify()
    }

    private fun descendants(container: Container): Sequence<Component> = container.components.asSequence().flatMap {
        sequenceOf(it) + if (it is Container) descendants(it) else emptySequence()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(instance: Any, name: String): T = instance.javaClass.getDeclaredField(name).let {
        it.isAccessible = true
        it.get(instance) as T
    }

    private fun eventually(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            var ready = false
            SwingUtilities.invokeAndWait { ready = predicate() }
            if (ready) return
            Thread.sleep(20)
        }
        fail("Timed out waiting for catalogue")
    }
}
