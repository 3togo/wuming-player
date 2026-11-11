package com.wuming.tv.desktop.ui

import com.sun.jna.Native
import com.wuming.tv.desktop.player.ExternalPlayerAdapter
import com.wuming.tv.desktop.player.PlaybackState
import com.wuming.tv.desktop.player.PlaybackStateCallback
import com.wuming.tv.desktop.player.PlaybackTargetPreparer
import com.wuming.tv.desktop.player.PreparedPlaybackTarget
import com.wuming.tv.desktop.player.playerProcessBuilder
import java.awt.GraphicsEnvironment
import java.awt.Robot
import java.awt.event.WindowEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.SwingUtilities
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Local synthetic media only. Run explicitly with :desktop:embeddedPlaybackTest under Xvfb. */
class EmbeddedPlaybackIntegrationTest {
    @Test fun realMpvRendersInTheApplicationAndPreservesItsSurfaceThroughFullscreenAndExternalFallback() {
        assumeTrue(java.lang.Boolean.getBoolean("wuming.embedded.integration") && !GraphicsEnvironment.isHeadless())
        val directory = Files.createTempDirectory("wuming-embedded-media-")
        val prefs = Preferences.userRoot().node("wuming-embedded-native/${System.nanoTime()}")
        prefs.putBoolean("playback.privateViewing", true)
        prefs.putBoolean("playback.autoPlayFirstEpisode", false)
        val media = directory.resolve("portrait.mp4")
        val ffmpeg = ProcessBuilder("ffmpeg", "-nostdin", "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=240x360:rate=15",
            "-t", "15", "-c:v", "mpeg4", "-y", media.toString()).redirectErrorStream(true).start()
        assertTrue(ffmpeg.waitFor(30, TimeUnit.SECONDS))
        assertEquals(ffmpeg.inputStream.bufferedReader().readText(), 0, ffmpeg.exitValue())
        val commands = CopyOnWriteArrayList<List<String>>()
        val player = ExternalPlayerAdapter(Path.of("/usr/bin/mpv"), { command ->
            commands += command
            playerProcessBuilder(command.dropLast(1) + listOf("--no-config", "--ao=null", "--vo=x11") + command.last())
                .redirectErrorStream(true).start()
        }, PlaybackTargetPreparer { location, _ -> PreparedPlaybackTarget(location) })
        val latest = AtomicReference(PlaybackState())
        player.addPlaybackStateListener(PlaybackStateCallback { latest.set(it) })
        var created = false
        lateinit var window: DesktopWindow
        lateinit var frame: JFrame
        lateinit var embedded: EmbeddedPlaybackPanel
        try {
            SwingUtilities.invokeAndWait {
                DesktopTvTheme.install(appearance = com.wuming.tv.core.design.Appearance.DARK)
                window = DesktopWindow(player, prefs, com.wuming.tv.desktop.server.DesktopLanRelay(
                    com.wuming.tv.desktop.server.DesktopLanRelaySettings(directory.resolve("relay.properties"))))
                frame = field(window, "frame")
                embedded = field(window, "embeddedPlayback")
                created = true
                frame.isVisible = true // Show the real shell without loading saved remote sources.
            }
            val started = CountDownLatch(1)
            assertTrue(player.play(media.toString(), onPlaybackStarted = { started.countDown() }).started)
            assertTrue("No decoded playback", started.await(15, TimeUnit.SECONDS))
            waitFor { latest.get().positionSeconds > 0.3 && latest.get().durationSeconds > 0 }
            assertTrue(commands.first().any { it.startsWith("--wid=") })
            val nativeId = commands.first().single { it.startsWith("--wid=") }.substringAfter('=').toLong()
            val tree = ProcessBuilder("xwininfo", "-id", nativeId.toString(), "-tree").start()
                .inputStream.bufferedReader().readText()
            assertTrue("mpv is not a child of the application surface: $tree", tree.contains("mpv", true))
            capture(frame, embedded, "embedded.png")
            assertCatalogueControlsFit(field(window, "catalog"))
            SwingUtilities.invokeAndWait {
                val split: javax.swing.JSplitPane = field(window, "playbackSplit")
                assertEquals(javax.swing.JSplitPane.HORIZONTAL_SPLIT, split.orientation)
                val rail: TvHomeNavigationPanel = field(window, "navigation")
                assertTrue(rail.isShowing)
                assertTrue(embedded.surface.width > 0)
                frame.setSize(900, 760)
            }
            waitFor { field<javax.swing.JSplitPane>(window, "playbackSplit").orientation == javax.swing.JSplitPane.VERTICAL_SPLIT }
            capture(frame, embedded, "compact.png")
            SwingUtilities.invokeAndWait {
                assertEquals(nativeId, Native.getComponentID(embedded.surface))
                DesktopTvTheme.refresh(com.wuming.tv.core.design.ColorTheme.CLASSIC,
                    com.wuming.tv.core.design.Appearance.LIGHT, false, listOf(frame))
            }
            capture(frame, embedded, "light.png")
            SwingUtilities.invokeAndWait {
                frame.setSize(DesktopTvTheme.px(1280), DesktopTvTheme.px(840))
                DesktopTvTheme.refresh(com.wuming.tv.core.design.ColorTheme.CLASSIC,
                    com.wuming.tv.core.design.Appearance.DARK, true, listOf(frame))
            }
            waitFor { field<javax.swing.JSplitPane>(window, "playbackSplit").orientation == javax.swing.JSplitPane.HORIZONTAL_SPLIT }
            SwingUtilities.invokeAndWait {
                frame.toFront()
                frame.requestFocus()
                embedded.surface.requestFocusInWindow()
            }
            waitFor { embedded.surface.isFocusOwner }
            val keyboard = Robot()
            keyboard.waitForIdle()
            keyboard.keyPress(java.awt.event.KeyEvent.VK_SPACE)
            keyboard.keyRelease(java.awt.event.KeyEvent.VK_SPACE)
            waitFor { latest.get().paused }
            assertTrue(player.seekTo(5.0))
            waitFor { latest.get().positionSeconds >= 4.5 }
            SwingUtilities.invokeAndWait {
                embedded.setFullscreen(true)
                assertEquals(nativeId, Native.getComponentID(embedded.surface))
            }
            SwingUtilities.invokeAndWait {
                assertEquals(frame.locationOnScreen, embedded.surface.locationOnScreen)
                assertEquals(frame.size, embedded.surface.size)
                assertFalse(embedded.fullscreenControlsVisible)
            }
            capture(frame, embedded, "fullscreen.png")
            keyboard.mouseMove(frame.x + frame.width / 2 + 37, frame.y + frame.height / 2)
            waitFor { embedded.fullscreenControlsVisible }
            capture(frame, embedded, "fullscreen-controls.png")
            waitFor { !embedded.fullscreenControlsVisible }
            SwingUtilities.invokeAndWait {
                // Exercise the production tray-hide callback without requiring a tray in Xvfb.
                val tray: Any = field(window, "tray")
                field<() -> Unit>(tray, "hide").invoke()
                assertFalse(frame.isVisible)
                assertFalse(embedded.fullscreenActive)
                assertNull(frame.graphicsConfiguration.device.fullScreenWindow)
                frame.isVisible = true
                embedded.setFullscreen(true)
                assertEquals(nativeId, Native.getComponentID(embedded.surface))
                embedded.surface.requestFocusInWindow()
            }
            keyboard.keyPress(java.awt.event.KeyEvent.VK_ESCAPE)
            keyboard.keyRelease(java.awt.event.KeyEvent.VK_ESCAPE)
            waitFor { !embedded.fullscreenActive }
            SwingUtilities.invokeAndWait {
                frame.setSize(1100, 760)
                assertEquals(nativeId, Native.getComponentID(embedded.surface))
            }
            val subtitle = directory.resolve("test.srt")
            Files.writeString(subtitle, "1\n00:00:00,000 --> 00:00:14,000\nEmbedded subtitle verification\n")
            assertTrue(player.addSubtitle(subtitle.toString()))
            waitFor { hasSubtitle(commands.first()) }
            capture(frame, embedded, "subtitles.png")
            assertTrue(player.setVolume(35))
            assertTrue(player.setMuted(true))
            waitFor { property(commands.first(), "volume")?.asDouble == 35.0 && property(commands.first(), "mute")?.asBoolean == true }
            assertTrue(player.setSpeed(1.5))
            assertTrue(player.togglePause())
            waitFor { !latest.get().paused && latest.get().speed == 1.5 }
            assertTrue(player.togglePause())
            waitFor { latest.get().paused }
            assertTrue(player.openExternally())
            waitFor { commands.size == 2 && hasSubtitle(commands.last()) &&
                property(commands.last(), "pause")?.asBoolean == true && property(commands.last(), "time-pos")?.asDouble?.let { it >= 4.5 } == true }
            assertTrue(player.togglePause())
            waitFor { latest.get().positionSeconds >= 6.0 }
            assertFalse(commands.last().any { it.startsWith("--wid=") })
            waitFor { property(commands.last(), "volume")?.asDouble == 35.0 && property(commands.last(), "mute")?.asBoolean == true }
            SwingUtilities.invokeAndWait { assertFalse(embedded.isVisible) }
            assertTrue(player.stop())
            assertFalse(player.isRunning())
            // Exercise real catalogue completion/advancement, rather than a helper-only callback.
            val catalog: CatalogPanel = field(window, "catalog")
            val line = com.wuming.tv.core.catalog.CatalogLine("direct", "Direct", listOf(
                com.wuming.tv.core.catalog.CatalogEpisode("First", "first"), com.wuming.tv.core.catalog.CatalogEpisode("Second", "second")))
            val detail = com.wuming.tv.core.catalog.CatalogDetail(com.wuming.tv.core.catalog.CatalogItem("synthetic", "series", "Synthetic series"), lines = listOf(line))
            val executor = object : com.wuming.tv.core.catalog.CatalogSourceExecutor {
                override val source = com.wuming.tv.core.catalog.CatalogSource("synthetic", "Synthetic")
                override fun search(query: String) = emptyList<com.wuming.tv.core.catalog.CatalogItem>()
                override fun detail(id: String) = detail
                override fun play(flag: String, id: String) = com.wuming.tv.core.catalog.CatalogPlayback(true, media.toString())
            }
            SwingUtilities.invokeAndWait {
                setField(catalog, "catalog", com.wuming.tv.core.catalog.ExecutorCatalogGateway(listOf(executor)))
                setField(catalog, "detail", detail)
                val lines: javax.swing.JComboBox<Any> = field(catalog, "lines")
                lines.addItem(line)
                val episodes: javax.swing.JList<Any> = field(catalog, "episodes")
                episodes.selectedIndex = 0
                catalog.javaClass.getDeclaredMethod("playEpisode", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(catalog, false)
            }
            waitFor { commands.size == 3 && latest.get().positionSeconds < 3 && latest.get().positionSeconds > 0.1 }
            SwingUtilities.invokeAndWait { embedded.setFullscreen(true) }
            assertTrue(player.seekTo(14.4))
            waitFor { commands.size == 4 && latest.get().positionSeconds < 3 && latest.get().positionSeconds > 0.1 }
            SwingUtilities.invokeAndWait {
                val episodes: javax.swing.JList<Any> = field(catalog, "episodes")
                assertEquals(1, episodes.selectedIndex)
                assertTrue(embedded.fullscreenActive)
            }
            SwingUtilities.invokeAndWait { field<javax.swing.JButton>(embedded, "stop").doClick() }
            waitFor { !player.isRunning() }
            SwingUtilities.invokeAndWait { assertNull(field<Any?>(catalog, "activeViewing")) }
            assertEquals(4, commands.size)
        } finally {
            player.terminateAll()
            if (created) SwingUtilities.invokeAndWait { frame.dispatchEvent(WindowEvent(frame, WindowEvent.WINDOW_CLOSING)) }
            prefs.removeNode()
            directory.toFile().deleteRecursively()
        }
    }

    @Test fun failedEmbeddingRetriesExternallyOnceWithoutPrematureFailureOrCloseCallbacks() {
        assumeTrue(java.lang.Boolean.getBoolean("wuming.embedded.integration") && !GraphicsEnvironment.isHeadless())
        val directory = Files.createTempDirectory("wuming-embedded-fallback-")
        val media = directory.resolve("clip.mp4")
        val ffmpeg = ProcessBuilder("ffmpeg", "-nostdin", "-v", "error", "-f", "lavfi", "-i", "color=blue:size=160x120:rate=10",
            "-t", "8", "-c:v", "mpeg4", "-y", media.toString()).redirectErrorStream(true).start()
        assertTrue(ffmpeg.waitFor(30, TimeUnit.SECONDS))
        assertEquals(0, ffmpeg.exitValue())
        val commands = CopyOnWriteArrayList<List<String>>()
        val closed = java.util.concurrent.atomic.AtomicInteger()
        val failed = java.util.concurrent.atomic.AtomicInteger()
        val resources = java.util.concurrent.atomic.AtomicInteger()
        val started = CountDownLatch(1)
        val player = ExternalPlayerAdapter(Path.of("/usr/bin/mpv"), { command ->
            commands += command
            val options = if (command.any { it.startsWith("--wid=") }) listOf("--vo=invalid-embedding-renderer") else listOf("--vo=x11")
            playerProcessBuilder(command.dropLast(1) + listOf("--no-config", "--ao=null") + options + command.last())
                .redirectErrorStream(true).start()
        }, PlaybackTargetPreparer { target, _ -> PreparedPlaybackTarget(target, AutoCloseable { resources.incrementAndGet() }) })
        player.setEmbeddedVideoHost(object : com.wuming.tv.desktop.player.EmbeddedVideoHost {
            override fun acquire(title: String?, fullscreen: Boolean): Long? = 1234L
            override fun externalPlayback() = Unit
            override fun playbackClosed() = Unit
        })
        try {
            assertTrue(player.play(media.toString(), onPlaybackStarted = { started.countDown() },
                onClosed = { closed.incrementAndGet() }, onPlaybackFailed = { failed.incrementAndGet() }).started)
            assertTrue("External retry did not start", started.await(15, TimeUnit.SECONDS))
            assertEquals(2, commands.size)
            assertTrue(commands.first().any { it.startsWith("--wid=") })
            assertFalse(commands.last().any { it.startsWith("--wid=") })
            assertEquals(0, failed.get())
            assertEquals(0, closed.get())
            player.stop()
            waitFor { resources.get() == 2 }
        } finally { player.terminateAll(); directory.toFile().deleteRecursively() }
    }

    @Suppress("UNCHECKED_CAST") private fun <T> field(target: Any, name: String): T =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target) as T

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(25)
        assertTrue("Timed out waiting for mpv state", condition())
    }

    private fun hasSubtitle(command: List<String>): Boolean = property(command, "track-list")?.asJsonArray?.any {
        it.asJsonObject.get("type")?.asString == "sub" && it.asJsonObject.get("selected")?.asBoolean == true && it.asJsonObject.get("external-filename")?.asString?.endsWith("test.srt") == true
    } == true

    private fun property(command: List<String>, name: String): com.google.gson.JsonElement? = runCatching {
        val socket = command.single { it.startsWith("--input-ipc-server=") }.substringAfter('=')
        java.nio.channels.SocketChannel.open(java.net.StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(java.net.UnixDomainSocketAddress.of(socket))
            val bytes = java.nio.ByteBuffer.wrap(("{\"command\":[\"get_property\",\"$name\"]}\n").toByteArray())
            while (bytes.hasRemaining()) channel.write(bytes)
            val response = java.nio.channels.Channels.newReader(channel, Charsets.UTF_8).buffered().readLine()
            com.google.gson.JsonParser.parseString(response).asJsonObject.get("data")
        }
    }.getOrNull()

    private fun setField(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun assertCatalogueControlsFit(catalog: CatalogPanel) {
        SwingUtilities.invokeAndWait {
            for (name in listOf("thumbnailsPerPage", "thumbnailPrevious", "thumbnailNext", "thumbnailPageLabel")) {
                val control: java.awt.Component = field(catalog, name)
                val parent = control.parent
                assertTrue("$name clipped horizontally", control.x >= 0 && control.x + control.width <= parent.width)
                assertTrue("$name clipped vertically", control.y >= 0 && control.y + control.height <= parent.height)
            }
        }
    }

    private fun capture(frame: JFrame, embedded: EmbeddedPlaybackPanel, name: String) {
        Thread.sleep(300)
        val directory = Path.of(System.getProperty("wuming.embedded.renderDirectory"))
        Files.createDirectories(directory)
        SwingUtilities.invokeAndWait {
            val image = Robot().createScreenCapture(frame.bounds)
            ImageIO.write(image, "png", directory.resolve(name).toFile())
            val surfaceImage = Robot().createScreenCapture(java.awt.Rectangle(embedded.surface.locationOnScreen, embedded.surface.size))
            var colored = 0
            for (y in 0 until surfaceImage.height step 8) for (x in 0 until surfaceImage.width step 8) {
                val color = java.awt.Color(surfaceImage.getRGB(x, y))
                if (maxOf(color.red, color.green, color.blue) - minOf(color.red, color.green, color.blue) > 80) colored++
            }
            assertTrue("Decoded timeline has no visible video pixels", colored > 10)
        }
    }
}
