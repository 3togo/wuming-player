package com.wuming.tv.desktop.ui

import com.wuming.tv.core.live.*
import com.wuming.tv.desktop.player.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JButton
import javax.swing.SwingUtilities
import org.junit.Assert.*
import org.junit.Test

class LiveTvPanelTest {
    @Test fun restoresSelectionAfterReorderingWithoutAutoplay() {
        val favorites = mutableSetOf<String>()
        val selections = mutableMapOf<String, LiveSelection>()
        val policy = LiveLibraryPolicy(object : LiveLibraryStore {
            override fun favorite(source: String, channel: String) = "$source:$channel" in favorites
            override fun setFavorite(source: String, channel: String, favorite: Boolean) {
                if (favorite) favorites.add("$source:$channel") else favorites.remove("$source:$channel")
            }
            override fun selection(source: String) = selections[source]
            override fun saveSelection(source: String, selection: LiveSelection) { selections[source] = selection }
        })
        val news = LiveChannel("News", tvgId = "news", streams = listOf(LiveStream("https://example.test/one"), LiveStream("https://example.test/two")))
        val sport = LiveChannel("Sport", tvgId = "sport", streams = listOf(LiveStream("https://example.test/sport")))
        policy.remember("source-a", news, 1)
        SwingUtilities.invokeAndWait {
            val panel = LiveTvPanel(null, library = policy)
            try {
                panel.javaClass.getDeclaredField("librarySource").apply { isAccessible = true; set(panel, "source-a") }
                val show = panel.javaClass.getDeclaredMethod("showPlaylist", LivePlaylist::class.java, String::class.java).apply { isAccessible = true }
                show.invoke(panel, LivePlaylist(listOf(LiveChannelGroup("TV", listOf(sport, news)))), null)
                assertEquals(1, field<javax.swing.JList<*>>(panel, "channels").selectedIndex)
                assertEquals(1, field<javax.swing.JComboBox<*>>(panel, "routes").selectedIndex)
                field<JButton>(panel, "favorite").doClick()
                assertTrue(policy.favorite("source-a", news))
                show.invoke(panel, LivePlaylist(listOf(LiveChannelGroup("TV", listOf(news.copy(streams = news.streams.reversed()), sport)))), null)
                assertEquals(0, field<javax.swing.JList<*>>(panel, "channels").selectedIndex)
                assertEquals(0, field<javax.swing.JComboBox<*>>(panel, "routes").selectedIndex)
                show.invoke(panel, LivePlaylist(listOf(LiveChannelGroup("TV", listOf(sport)))), null)
                assertTrue(field<javax.swing.JLabel>(panel, "status").text.contains("Previous channel is unavailable"))
                assertFalse(field<JButton>(panel, "play").isEnabled)
            } finally { panel.close() }
        }
    }

    @Test fun slowPreparationLeavesUiResponsive() = exercise(cancel = false)
    @Test fun closingDuringPreparationPreventsPlayerLaunch() = exercise(cancel = true)

    private fun exercise(cancel: Boolean) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/video") { exchange ->
            exchange.sendResponseHeaders(200, 1)
            exchange.responseBody.use { it.write(1) }
        }
        server.start()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val started = AtomicInteger()
        val preparedOnEdt = AtomicBoolean()
        val player = ExternalPlayerAdapter(Path.of("/usr/bin/mpv"), { started.incrementAndGet(); null }, PlaybackTargetPreparer { target, _ ->
            preparedOnEdt.set(SwingUtilities.isEventDispatchThread())
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            PreparedPlaybackTarget(target, if (cancel) AutoCloseable { finished.countDown() } else null)
        })
        lateinit var panel: LiveTvPanel
        var panelCreated = false
        try {
            SwingUtilities.invokeAndWait {
                panel = LiveTvPanel(player)
                panelCreated = true
                val playlist = LivePlaylist(listOf(LiveChannelGroup("News", listOf(
                    LiveChannel("News", streams = listOf(LiveStream("http://127.0.0.1:${server.address.port}/video"))),
                ))))
                panel.javaClass.getDeclaredMethod("showPlaylist", LivePlaylist::class.java, String::class.java).apply {
                    isAccessible = true
                    invoke(panel, playlist, null)
                }
                field<JButton>(panel, "play").addPropertyChangeListener("enabled") {
                    if (it.newValue == true) finished.countDown()
                }
                field<JButton>(panel, "play").doClick()
            }
            assertTrue(entered.await(4, TimeUnit.SECONDS))
            val responsive = CountDownLatch(1)
            SwingUtilities.invokeLater {
                if (cancel) panel.close()
                responsive.countDown()
            }
            assertTrue("EDT must remain responsive while preparing media", responsive.await(1, TimeUnit.SECONDS))
            assertFalse(preparedOnEdt.get())
            release.countDown()
            if (cancel) {
                // Drain the background worker and verify the adapter's cancellation check after preparation.
                assertTrue(finished.await(4, TimeUnit.SECONDS))
                assertEquals(0, started.get())
            } else {
                assertTrue(finished.await(4, TimeUnit.SECONDS))
                assertEquals(1, started.get())
            }
        } finally {
            release.countDown()
            SwingUtilities.invokeAndWait { if (panelCreated) panel.close() }
            server.stop(0)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(instance: Any, name: String): T = instance.javaClass.getDeclaredField(name).let {
        it.isAccessible = true
        it.get(instance) as T
    }
}
