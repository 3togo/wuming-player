package com.wuming.tv.desktop.ui

import com.wuming.tv.core.catalog.CatalogDetail
import com.wuming.tv.core.catalog.CatalogEpisode
import com.wuming.tv.core.catalog.CatalogItem
import com.wuming.tv.core.catalog.CatalogLine
import com.wuming.tv.core.catalog.CatalogPlayback
import com.wuming.tv.core.catalog.CatalogSource
import com.wuming.tv.core.catalog.CatalogSourceExecutor
import com.wuming.tv.core.catalog.ExecutorCatalogGateway
import com.wuming.tv.desktop.player.ExternalPlayerAdapter
import com.wuming.tv.desktop.player.PlaybackTargetPreparer
import com.wuming.tv.desktop.player.PreparedPlaybackTarget
import java.awt.Component
import java.awt.Container
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.prefs.Preferences
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JProgressBar
import javax.swing.SwingUtilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogPanelPlaybackTest {

    @Test
    fun `progress bar is hidden initially`() {
        val (panel, preferences) = createPanel()
        try {
            val progressBar = findComponent(panel, JProgressBar::class.java)
            assertFalse(progressBar.isVisible)
        } finally {
            closePanel(panel)
            preferences.removeNode()
        }
    }

    @Test
    fun `startup indicator advances only by stage and stays compact`() {
        val (panel, preferences) = createPanel()
        try {
            onEdt {
                val bar = findComponent(panel, JProgressBar::class.java)
                setField(panel, "playbackStartupNanos", System.nanoTime())
                invoke(panel, "showProgress", true)
                for (step in 1..3) {
                    setField(panel, "playbackStartupStep", step)
                    invoke(panel, "refreshPlaybackStartup")
                    assertFalse(bar.isIndeterminate)
                    assertEquals(step - 1, bar.value)
                }
                setField(panel, "playbackStartupNanos", System.nanoTime() - TimeUnit.SECONDS.toNanos(40))
                invoke(panel, "refreshPlaybackStartup")
                assertEquals(2, bar.value) // Time alone must not invent progress.
                panel.setSize(1270, 800)
                fun layout(container: Container) {
                    container.doLayout()
                    container.components.filterIsInstance<Container>().forEach(::layout)
                }
                layout(panel)
                assertEquals(72, bar.width)
                assertEquals(4, bar.height)
            }
        } finally { closePanel(panel); preferences.removeNode() }
    }

    @Test
    fun `replay button is present`() {
        val (panel, preferences) = createPanel()
        try {
            val replayButton = findButtonByText(panel, "Replay")
            assertTrue(replayButton.isVisible)
        } finally {
            closePanel(panel)
            preferences.removeNode()
        }
    }

    @Test
    fun `speed button is present with initial label`() {
        val (panel, preferences) = createPanel()
        try {
            val speedButton = findButtonByText(panel, "1.0x")
            assertEquals("1.0x", speedButton.text)
        } finally {
            closePanel(panel)
            preferences.removeNode()
        }
    }

    @Test
    fun `subtitle button is present`() {
        val (panel, preferences) = createPanel()
        try {
            findButtonByText(panel, "Sub")
        } finally {
            closePanel(panel)
            preferences.removeNode()
        }
    }

    @Test
    fun `pause button is present`() {
        val (panel, preferences) = createPanel()
        try {
            findButtonByText(panel, "Pause")
        } finally {
            closePanel(panel)
            preferences.removeNode()
        }
    }

    @Test
    fun `pause button shows Play label when paused via state callback`() {
        val (panel, preferences) = createPanel()
        try {
            val pauseButton = findButtonByText(panel, "Pause")
            assertEquals("❚❚ Pause", pauseButton.text)

            onEdt {
                val callbackField = panel.javaClass.getDeclaredField("playerPause")
                callbackField.isAccessible = true
                val callback = callbackField.get(panel) as JButton
                val updateMethod = panel.javaClass.getDeclaredMethod(
                    "updatePlaybackState",
                    com.wuming.tv.desktop.player.PlaybackState::class.java,
                )
                updateMethod.isAccessible = true
                updateMethod.invoke(panel, com.wuming.tv.desktop.player.PlaybackState(paused = true))
            }

            assertEquals("▶ Play", pauseButton.text)
        } finally {
            closePanel(panel)
            preferences.removeNode()
        }
    }

    @Test
    fun `autoNext checkbox reflects playback settings`() {
        val preferences = Preferences.userRoot().node("tvbox-test/${System.nanoTime()}")
        try {
            val settings = com.wuming.tv.desktop.config.DesktopPlaybackSettings(preferences)
            settings.autoPlayNextEpisode = false

            val panel = createPanelOnEdt(preferences)
            try {
                val autoNext = findComponent(panel, JCheckBox::class.java)
                assertFalse(autoNext.isSelected)
            } finally {
                closePanel(panel)
            }
        } finally {
            preferences.removeNode()
        }
    }

    @Test
    fun `action bar groups buttons into separate rows`() {
        val (panel, preferences) = createPanel()
        try {
            val replayButton = findButtonByText(panel, "Replay")
            val playButton = findButtonByText(panel, "Play episode")
            val speedButton = findButtonByText(panel, "1.0x")

            assertTrue(
                "Replay and speed should be in separate rows from play",
                replayButton.parent !== playButton.parent || speedButton.parent !== playButton.parent,
            )
        } finally {
            closePanel(panel)
            preferences.removeNode()
        }
    }

    @Test
    fun `play button is disabled while resolving`() {
        val preferences = Preferences.userRoot().node("tvbox-test/${System.nanoTime()}")
        val releaseLatch = CountDownLatch(1)
        val panel = createSlowPanel(preferences, releaseLatch)
        try {
            val detail = CatalogDetail(
                item = CatalogItem("provider", "show", "Show"),
                lines = listOf(CatalogLine("line", "Line", listOf(CatalogEpisode("First", "first")))),
            )
            onEdt {
                setField(panel, "detail", detail)
                setupEpisodes(panel)
                invoke(panel, "playEpisode", false)
            }

            Thread.sleep(200)
            val playButton = findButtonByText(panel, "Play episode")
            assertFalse(playButton.isEnabled)

            releaseLatch.countDown()
            Thread.sleep(300)
            assertTrue(playButton.isEnabled)
        } finally {
            releaseLatch.countDown()
            closePanel(panel)
            preferences.removeNode()
        }
    }

    @Test
    fun `progress bar shows during resolution`() {
        val preferences = Preferences.userRoot().node("tvbox-test/${System.nanoTime()}")
        val releaseLatch = CountDownLatch(1)
        val panel = createSlowPanel(preferences, releaseLatch)
        try {
            val progressBar = findComponent(panel, JProgressBar::class.java)
            assertFalse(progressBar.isVisible)

            val detail = CatalogDetail(
                item = CatalogItem("provider", "show", "Show"),
                lines = listOf(CatalogLine("line", "Line", listOf(CatalogEpisode("First", "first")))),
            )
            onEdt {
                setField(panel, "detail", detail)
                setupEpisodes(panel)
                invoke(panel, "playEpisode", false)
            }

            Thread.sleep(200)
            assertTrue(progressBar.isVisible)

            releaseLatch.countDown()
            Thread.sleep(300)
            assertFalse(progressBar.isVisible)
        } finally {
            releaseLatch.countDown()
            closePanel(panel)
            preferences.removeNode()
        }
    }

    @Test
    fun `slow preparation keeps UI responsive shows elapsed time and can be cancelled`() {
        val preferences = Preferences.userRoot().node("tvbox-test/${System.nanoTime()}")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val launches = java.util.concurrent.atomic.AtomicInteger()
        val player = ExternalPlayerAdapter(Path.of("/usr/bin/mpv"), { launches.incrementAndGet(); null },
            PlaybackTargetPreparer { target, _ ->
                assertFalse(SwingUtilities.isEventDispatchThread())
                started.countDown()
                try { check(release.await(5, TimeUnit.SECONDS)) } finally { finished.countDown() }
                PreparedPlaybackTarget(target)
            })
        val panel = createPanelOnEdt(preferences, player)
        try {
            onEdt {
                setField(panel, "catalog", ExecutorCatalogGateway(listOf(simpleExecutor())))
                setField(panel, "detail", simpleExecutor().detail("show"))
                setupEpisodes(panel)
                invoke(panel, "playEpisode", false)
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            onEdt {
                setField(panel, "playbackStartupNanos", System.nanoTime() - TimeUnit.SECONDS.toNanos(25))
                invoke(panel, "refreshPlaybackStartup")
                val label = panel.javaClass.getDeclaredField("playbackStartup").apply { isAccessible = true }.get(panel) as javax.swing.JLabel
                assertTrue(label.text.contains("Preparing and opening mpv"))
                assertTrue(label.text.contains("25s"))
                assertTrue(label.text.contains("Still waiting"))
                val stop = panel.javaClass.getDeclaredField("playerStop").apply { isAccessible = true }.get(panel) as JButton
                assertTrue(stop.isEnabled)
                stop.doClick()
                assertTrue(label.text.contains("cancelled"))
                assertFalse(findComponent(panel, JProgressBar::class.java).isVisible)
            }
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            Thread.sleep(150)
            assertEquals(0, launches.get())
        } finally {
            release.countDown()
            closePanel(panel)
            preferences.removeNode()
        }
    }

    @Test
    fun `playback position completes startup timer`() {
        val (panel, preferences) = createPanel()
        try {
            onEdt {
                setField(panel, "playbackStartupNanos", System.nanoTime() - TimeUnit.SECONDS.toNanos(4))
                val timer = panel.javaClass.getDeclaredField("playbackStartupTimer").apply { isAccessible = true }.get(panel) as javax.swing.Timer
                timer.start()
                invoke(panel, "updatePlaybackState", com.wuming.tv.desktop.player.PlaybackState(positionSeconds = 0.1))
                assertFalse(timer.isRunning)
                val label = panel.javaClass.getDeclaredField("playbackStartup").apply { isAccessible = true }.get(panel) as javax.swing.JLabel
                assertTrue(label.text.contains("Playback started"))
                assertTrue(label.text.contains("4s elapsed"))
            }
        } finally { closePanel(panel); preferences.removeNode() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun setupEpisodes(panel: CatalogPanel) {
        val line = CatalogLine("line", "Line", listOf(CatalogEpisode("First", "first")))
        val linesField = panel.javaClass.getDeclaredField("lines")
        linesField.isAccessible = true
        val linesCombo = linesField.get(panel) as JComboBox<Any>
        linesCombo.addItem(line)
        linesCombo.selectedIndex = 0
        val episodesField = panel.javaClass.getDeclaredField("episodes")
        episodesField.isAccessible = true
        val episodesList = episodesField.get(panel) as JList<Any>
        episodesList.selectedIndex = 0
    }

    private fun createPanel(): Pair<CatalogPanel, Preferences> {
        val preferences = Preferences.userRoot().node("tvbox-test/${System.nanoTime()}")
        val executor = simpleExecutor()
        val player = ExternalPlayerAdapter(
            Path.of("/usr/bin/mpv"),
            { null },
            PlaybackTargetPreparer { target, _ -> PreparedPlaybackTarget(target) },
        )
        val panel = createPanelOnEdt(preferences, player)
        onEdt { setField(panel, "catalog", ExecutorCatalogGateway(listOf(executor))) }
        return panel to preferences
    }

    private fun createPanelOnEdt(
        preferences: Preferences,
        player: ExternalPlayerAdapter = ExternalPlayerAdapter(
            Path.of("/usr/bin/mpv"),
            { null },
            PlaybackTargetPreparer { target, _ -> PreparedPlaybackTarget(target) },
        ),
    ): CatalogPanel {
        val ref = AtomicReference<CatalogPanel>()
        onEdt { ref.set(CatalogPanel(player, preferences)) }
        return ref.get()
    }

    private fun createSlowPanel(preferences: Preferences, releaseLatch: CountDownLatch): CatalogPanel {
        val executor = object : CatalogSourceExecutor {
            override val source = CatalogSource("provider", "Provider")
            override fun search(query: String) = emptyList<CatalogItem>()
            override fun detail(id: String) = CatalogDetail(
                item = CatalogItem("provider", id, "Show"),
                lines = listOf(CatalogLine("line", "Line", listOf(CatalogEpisode("First", "first")))),
            )
            override fun play(flag: String, id: String): CatalogPlayback {
                releaseLatch.await(5, TimeUnit.SECONDS)
                return CatalogPlayback(supported = true, url = "https://media.example/$id.mp4")
            }
        }
        val player = ExternalPlayerAdapter(
            Path.of("/usr/bin/mpv"),
            { null },
            PlaybackTargetPreparer { target, _ -> PreparedPlaybackTarget(target) },
        )
        val panel = createPanelOnEdt(preferences, player)
        onEdt { setField(panel, "catalog", ExecutorCatalogGateway(listOf(executor))) }
        return panel
    }

    private fun simpleExecutor() = object : CatalogSourceExecutor {
        override val source = CatalogSource("provider", "Provider")
        override fun search(query: String) = emptyList<CatalogItem>()
        override fun detail(id: String) = CatalogDetail(
            item = CatalogItem("provider", id, "Show"),
            lines = listOf(CatalogLine("line", "Line", listOf(CatalogEpisode("First", "first")))),
        )
        override fun play(flag: String, id: String) = CatalogPlayback(supported = true, url = "https://media.example/$id.mp4")
    }

    private fun closePanel(panel: CatalogPanel) {
        onEdt { invoke(panel, "close") }
    }

    private fun onEdt(action: () -> Unit) {
        SwingUtilities.invokeAndWait(action)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Component> findComponent(root: Container, type: Class<T>): T {
        val found = mutableListOf<Component>()
        collect(root, type, found)
        if (found.isEmpty()) throw AssertionError("No ${type.simpleName} found in ${root.javaClass.simpleName}")
        return found[0] as T
    }

    private fun findButtonByText(root: Container, text: String): JButton {
        val found = mutableListOf<Component>()
        collect(root, JButton::class.java, found)
        return found.map { it as JButton }.first { it.text.contains(text) }
    }

    private fun <T : Component> collect(root: Container, type: Class<T>, result: MutableList<Component>) {
        for (component in root.components) {
            if (type.isInstance(component)) result.add(component)
            if (component is Container) collect(component, type, result)
        }
    }

    private fun setField(target: Any, name: String, value: Any?) {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun invoke(target: Any, name: String, vararg args: Any?) {
        val paramTypes = args.map { arg ->
            when (arg) {
                is Boolean -> Boolean::class.javaPrimitiveType
                is Int -> Int::class.javaPrimitiveType
                else -> arg?.javaClass ?: Any::class.java
            }
        }.toTypedArray()
        val method = target.javaClass.getDeclaredMethod(name, *paramTypes)
        method.isAccessible = true
        method.invoke(target, *args)
    }
}
