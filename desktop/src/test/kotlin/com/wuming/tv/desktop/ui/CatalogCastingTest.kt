package com.wuming.tv.desktop.ui

import com.wuming.tv.core.catalog.*
import com.wuming.tv.desktop.library.DesktopUserLibrary
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.prefs.Preferences
import javax.swing.*
import org.junit.Assert.*
import org.junit.Test

class CatalogCastingTest {
    @Test fun resolvesSelectedEpisodeOffTheUiThreadAndPreservesPlaybackHeaders() = verify(true)
    @Test fun unsupportedEpisodeDoesNotOpenTheCastingScreen() = verify(false)

    private fun verify(supported: Boolean) {
        val preferences = Preferences.userRoot().node("tvbox-cast-test/${System.nanoTime()}")
        val directory = Files.createTempDirectory("catalog-cast-test-")
        val result = CatalogPlayback(supported = supported, url = "https://media.example/video.m3u8",
            headers = mapOf("Referer" to "https://provider.example/", "Cookie" to "private-session"),
            message = if (supported) "" else "Account required")
        val detail = CatalogDetail(CatalogItem("provider", "show", "Show"),
            lines = listOf(CatalogLine("opaque-line", "Line", listOf(CatalogEpisode("Episode 1", "opaque-id")))))
        val executor = object : CatalogSourceExecutor {
            override val source = CatalogSource("provider", "Provider")
            override fun search(query: String) = emptyList<CatalogItem>()
            override fun detail(id: String) = detail
            override fun play(flag: String, id: String): CatalogPlayback {
                assertFalse(SwingUtilities.isEventDispatchThread())
                assertEquals("opaque-line", flag)
                assertEquals("opaque-id", id)
                return result
            }
        }
        val handed = AtomicReference<CatalogPlayback>()
        val title = AtomicReference<String>()
        val sent = AtomicReference<() -> Unit>()
        val completed = CountDownLatch(1)
        val panel = AtomicReference<CatalogPanel>()
        try {
            SwingUtilities.invokeAndWait {
                val value = CatalogPanel(null, preferences, userLibrary = DesktopUserLibrary.open(directory.resolve("library.json")),
                    onCastRequested = { playback, name, onSent ->
                        assertTrue(SwingUtilities.isEventDispatchThread())
                        handed.set(playback); title.set(name); sent.set(onSent)
                    })
                panel.set(value)
                field(value, "playbackGeneration").setLong(value, 12L)
                field(value, "catalog").set(value, ExecutorCatalogGateway(listOf(executor)))
                field(value, "detail").set(value, detail)
                @Suppress("UNCHECKED_CAST")
                val lines = field(value, "lines").get(value) as JComboBox<CatalogLine>
                lines.model = DefaultComboBoxModel(detail.lines.toTypedArray())
                value.javaClass.getDeclaredMethod("refreshEpisodes").apply { isAccessible = true }.invoke(value)
                val cast = field(value, "cast").get(value) as JButton
                assertTrue(cast.isEnabled) // Casting does not require a local player.
                cast.addPropertyChangeListener("enabled") { if (it.newValue == true) completed.countDown() }
                cast.doClick()
            }
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            SwingUtilities.invokeAndWait {
                if (supported) {
                    assertEquals(12L, field(panel.get(), "playbackGeneration").getLong(panel.get()))
                    assertEquals(result, handed.get())
                    assertEquals("Show — Episode 1", title.get())
                    sent.get().invoke()
                    assertEquals(13L, field(panel.get(), "playbackGeneration").getLong(panel.get()))
                } else {
                    assertNull(handed.get())
                    assertEquals("Account required", (field(panel.get(), "status").get(panel.get()) as JLabel).text)
                }
            }
        } finally {
            SwingUtilities.invokeAndWait { panel.get()?.close() }
            preferences.removeNode()
            directory.toFile().deleteRecursively()
        }
    }

    private fun field(panel: CatalogPanel, name: String) = panel.javaClass.getDeclaredField(name).apply { isAccessible = true }
}
