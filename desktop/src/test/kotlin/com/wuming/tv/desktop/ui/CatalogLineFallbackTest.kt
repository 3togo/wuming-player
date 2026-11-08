package com.wuming.tv.desktop.ui

import com.wuming.tv.core.catalog.*
import com.wuming.tv.desktop.player.*
import com.wuming.tv.desktop.library.DesktopUserLibrary
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.*
import java.util.prefs.Preferences
import javax.swing.*
import org.junit.Assert.*
import org.junit.Test

class CatalogLineFallbackTest {
    private val lines = listOf(
        CatalogLine("FF", "FF", listOf(CatalogEpisode("第01集", "ff1"), CatalogEpisode("第02集", "ff2"))),
        CatalogLine("YZ", "YZ", listOf(CatalogEpisode("Episode 2", "yz2"), CatalogEpisode("Episode 1", "yz1"))),
        CatalogLine("RY", "RY", listOf(CatalogEpisode("第01集", "ry1"), CatalogEpisode("第02集", "ry2"))),
    )

    @Test fun resolverExceptionsAndUnsupportedResultsAdvanceWithoutChangingTheEpisode() {
        val calls = CopyOnWriteArrayList<String>()
        Fixture(lines, resolve = { flag, id ->
            calls += "$flag:$id"
            when (flag) {
                "FF" -> error("provider failed")
                "YZ" -> CatalogPlayback(message = "line unsupported")
                else -> CatalogPlayback(true, "https://fixture.test/$id.mp4")
            }
        }).use { f ->
            f.play(1)
            eventually { f.launches.size == 1 }
            assertEquals(listOf("FF:ff2", "YZ:yz2", "RY:ry2"), calls)
            onEdt { assertEquals(2, f.lineIndex()); assertEquals(1, f.episodeIndex()) }
        }
    }

    @Test fun invalidPlaylistPreparationAutomaticallyTriesTheNextLine() {
        Fixture(lines, prepare = { target, _ ->
            if (target.contains("ff")) throw PlaybackPreparationException("invalid HLS playlist")
            PreparedPlaybackTarget(target)
        }).use { f ->
            f.play(0)
            eventually { f.launches.size == 1 }
            assertTrue(f.launches.single().last().endsWith("yz1.mp4"))
            onEdt { assertEquals(1, f.lineIndex()); assertEquals(1, f.episodeIndex()) }
        }
    }

    @Test fun exhaustsLinesOnceAndKeepsTheLastFailureVisible() {
        val calls = CopyOnWriteArrayList<String>()
        Fixture(lines, resolve = { flag, _ -> calls += flag; CatalogPlayback(message = "$flag unavailable") }).use { f ->
            f.play(0)
            eventually { f.status().contains("No more matching") }
            assertEquals(listOf("FF", "YZ", "RY"), calls)
            assertTrue(f.status().contains("RY unavailable"))
            assertTrue(f.launches.isEmpty())
        }
    }

    @Test fun stopDuringResolutionSuppressesThePendingFallback() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = CopyOnWriteArrayList<String>()
        Fixture(lines, resolve = { flag, _ ->
            calls += flag; entered.countDown(); release.await(5, TimeUnit.SECONDS)
            CatalogPlayback(message = "late failure")
        }).use { f ->
            try {
                f.play(0)
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                onEdt { f.invoke("stopExternalPlayer") }
                release.countDown()
                eventually { f.status().contains("cancelled") }
                // Drain the completed resolver's EDT callback before asserting no retry.
                Thread.sleep(150)
                onEdt { assertEquals(listOf("FF"), calls); assertEquals(0, f.lineIndex()) }
            } finally { release.countDown() }
        }
    }

    @Test fun manualSelectionDuringResolutionPreventsTheOldFailureFromSwitchingLines() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = CopyOnWriteArrayList<String>()
        Fixture(lines, resolve = { flag, _ ->
            calls += flag; entered.countDown(); release.await(5, TimeUnit.SECONDS)
            CatalogPlayback(message = "late failure")
        }).use { f ->
            try {
                f.play(0)
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                onEdt { (f.field("lines") as JComboBox<*>).selectedIndex = 1 }
                release.countDown()
                eventually { f.status() == "late failure" }
                assertEquals(listOf("FF"), calls)
                onEdt { assertEquals(1, f.lineIndex()) }
            } finally { release.countDown() }
        }
    }

    @Test fun returningToTheOriginalSelectionStillCancelsItsQueuedFallback() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = CopyOnWriteArrayList<String>()
        Fixture(lines, resolve = { flag, _ ->
            calls += flag; entered.countDown(); release.await(5, TimeUnit.SECONDS)
            CatalogPlayback(message = "late failure")
        }).use { f ->
            try {
                f.play(0)
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                onEdt { (f.field("lines") as JComboBox<*>).apply { selectedIndex = 1; selectedIndex = 0 } }
                release.countDown()
                eventually { f.status() == "late failure" }
                assertEquals(listOf("FF"), calls)
                onEdt { assertEquals(0, f.lineIndex()) }
            } finally { release.countDown() }
        }
    }

    @Test fun anMpvErrorBeforeTheFirstFrameAlsoAdvancesToTheNextLine() = checkMpvFallback(null)

    @Test fun aFailureAfterPlaybackResumesTheSameEpisodeOnTheNextLine() = checkMpvFallback(12.0)

    private fun checkMpvFallback(position: Double?) {
        val server = java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)
        val process = FakeProcess()
        val bound = CountDownLatch(1)
        val loadedNextLine = CountDownLatch(1)
        val readyToFail = CountDownLatch(1)
        val resumed = CountDownLatch(1)
        var socket: Path? = null
        Fixture(lines, start = { command ->
            if (command.last().contains("ff")) {
                socket = Path.of(command.single { it.startsWith("--input-ipc-server=") }.substringAfter('='))
                server.bind(java.net.UnixDomainSocketAddress.of(socket))
                bound.countDown()
                process
            } else null
        }).use { f ->
            val worker = Thread {
                check(bound.await(5, TimeUnit.SECONDS))
                server.accept().use { channel ->
                    val writer = java.nio.channels.Channels.newWriter(channel, Charsets.UTF_8).buffered()
                    if (position != null) {
                        check(readyToFail.await(5, TimeUnit.SECONDS))
                        writer.write("""{"event":"playback-restart"}""" + "\n")
                        writer.write("""{"event":"property-change","name":"duration","data":1000}""" + "\n")
                        writer.write("""{"event":"property-change","name":"time-pos","data":$position}""" + "\n")
                    }
                    writer.write("""{"event":"end-file","reason":"error","file_error":"unrecognized file format"}""")
                    writer.newLine(); writer.flush()
                    // Keep the old IPC connection alive until the next attempt replaces it.
                    val reader = java.nio.channels.Channels.newReader(channel, Charsets.UTF_8).buffered()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.contains("yz1.mp4")) {
                            writer.write("""{"event":"playback-restart"}""")
                            writer.newLine(); writer.flush()
                            loadedNextLine.countDown()
                        }
                        if (line.contains("\"seek\"") && line.contains("12.0")) resumed.countDown()
                        if (line.contains("quit")) break
                    }
                }
                process.destroy()
            }.apply { isDaemon = true; start() }
            try {
                f.play(0)
                if (position != null) {
                    eventually { f.status().startsWith("Opened in") }
                    readyToFail.countDown()
                }
                assertTrue(loadedNextLine.await(5, TimeUnit.SECONDS))
                if (position != null) assertTrue("Fallback must resume the observed position", resumed.await(5, TimeUnit.SECONDS))
                assertEquals(1, f.launches.size) // Reuses the idle external mpv session with the new line.
                onEdt { assertEquals(1, f.lineIndex()); assertEquals(1, f.episodeIndex()) }
            } finally {
                readyToFail.countDown(); process.destroy(); server.close(); worker.join(1000); socket?.let(Files::deleteIfExists)
            }
        }
    }

    private class FakeProcess : Process() {
        private val exit = CompletableFuture<Process>()
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int { exit.get(); return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = try { exit.get(timeout, unit); true } catch (_: TimeoutException) { false }
        override fun exitValue() = 0
        override fun destroy() { exit.complete(this) }
        override fun isAlive() = !exit.isDone
        override fun pid() = 123456L
        override fun onExit() = exit
    }

    private class Fixture(
        val lines: List<CatalogLine>,
        resolve: (String, String) -> CatalogPlayback = { _, id -> CatalogPlayback(true, "https://fixture.test/$id.mp4") },
        prepare: (String, Map<String, String>) -> PreparedPlaybackTarget = { url, _ -> PreparedPlaybackTarget(url) },
        start: (List<String>) -> Process? = { null },
    ) : AutoCloseable {
        val launches = CopyOnWriteArrayList<List<String>>()
        private val prefs = Preferences.userRoot().node("fallback-test/${System.nanoTime()}")
        private val directory = Files.createTempDirectory("catalog-fallback-")
        private val player = ExternalPlayerAdapter(Path.of("/usr/bin/mpv"), { command -> launches += command; start(command) },
            PlaybackTargetPreparer(prepare), singleWindow = { true })
        lateinit var panel: CatalogPanel
        init {
            prefs.putBoolean("playback.autoPlayFirstEpisode", false)
            val executor = object : CatalogSourceExecutor {
                override val source = CatalogSource("fixture", "Fixture")
                override fun search(query: String) = emptyList<CatalogItem>()
                override fun detail(id: String) = CatalogDetail(CatalogItem(source.key, id, "Show"), lines = lines)
                override fun play(flag: String, id: String) = resolve(flag, id)
            }
            onEdt {
                panel = CatalogPanel(player, prefs, userLibrary = DesktopUserLibrary.open(directory.resolve("library.json")))
                set("catalog", ExecutorCatalogGateway(listOf(executor)))
                set("detail", executor.detail("show"))
                @Suppress("UNCHECKED_CAST")
                val choices = field("lines") as JComboBox<CatalogLine>
                choices.model = DefaultComboBoxModel(lines.toTypedArray())
                invoke("refreshEpisodes")
            }
        }
        fun play(episode: Int) = onEdt {
            (field("episodes") as JList<*>).selectedIndex = episode
            invoke("playEpisode", false)
        }
        fun lineIndex() = (field("lines") as JComboBox<*>).selectedIndex
        fun episodeIndex() = (field("episodes") as JList<*>).selectedIndex
        fun status(): String { var value = ""; onEdt { value = (field("status") as JLabel).text }; return value }
        fun invoke(name: String, vararg args: Any) {
            panel.javaClass.getDeclaredMethod(name, *args.map { if (it is Boolean) Boolean::class.javaPrimitiveType else it.javaClass }.toTypedArray())
                .apply { isAccessible = true }.invoke(panel, *args)
        }
        fun field(name: String): Any = panel.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(panel)
        private fun set(name: String, value: Any) { panel.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(panel, value) }
        override fun close() { onEdt { panel.close() }; player.terminateAll(); prefs.removeNode(); directory.toFile().deleteRecursively() }
    }

    companion object {
        private fun onEdt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)
        private fun eventually(condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) { if (condition()) return; Thread.sleep(20) }
            assertTrue("Fallback did not reach expected state", condition())
        }
    }
}
