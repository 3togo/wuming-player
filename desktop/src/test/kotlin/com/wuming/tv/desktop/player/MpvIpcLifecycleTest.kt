package com.wuming.tv.desktop.player

import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** A real, quiet Unix socket exercises full-duplex IPC without mpv or network access. */
class MpvIpcLifecycleTest {
    @Test fun controlsRemainResponsiveWhileTheEventReaderIsWaiting() {
        Fixture().use { fixture ->
            fixture.start()
            fixture.awaitReader()
            assertTrue(fixture.request { fixture.player.togglePause() })
            assertEquals(listOf("cycle", "pause"), fixture.readCommand())
            assertTrue(fixture.request { fixture.player.seekTo(42.0) })
            assertEquals(listOf("seek", "42.0", "absolute"), fixture.readCommand())
            assertTrue(fixture.request { fixture.player.setSpeed(1.5) })
            assertEquals(listOf("set_property", "speed", "1.5"), fixture.readCommand())
        }
    }

    @Test fun subtitlePathsEscapeEveryJsonControlCharacter() {
        Fixture().use { fixture ->
            fixture.start()
            val path = "/tmp/字幕 \"quoted\"\t\b\u000c\u0001.srt"
            assertTrue(fixture.request { fixture.player.addSubtitle(path) })
            assertEquals(listOf("sub-add", path, "cached"), fixture.readCommand())
        }
    }

    @Test fun concurrentControlsKeepCompleteJsonMessages() {
        Fixture().use { fixture ->
            fixture.start()
            val workers = Executors.newFixedThreadPool(4)
            try {
                val requests = (1..24).map { index -> workers.submit<Boolean> { fixture.player.seekTo(index.toDouble()) } }
                assertTrue(requests.all { it.get(3, TimeUnit.SECONDS) })
                val positions = (1..24).map {
                    val command = fixture.readCommand()
                    assertEquals("seek", command[0])
                    assertEquals("absolute", command[2])
                    command[1].toDouble().toInt()
                }
                assertEquals((1..24).toSet(), positions.toSet())
            } finally { workers.shutdownNow() }
        }
    }

    @Test fun explicitStopAndProcessExitReleaseEachRelayOnlyOnce() {
        Fixture().use { fixture ->
            fixture.start()
            fixture.peer.close()
            assertTrue(fixture.request { fixture.player.stop() })
            assertFalse(fixture.player.isRunning())
            assertEquals(1, fixture.released.get())
            assertFalse(fixture.player.stop())
            assertEquals(1, fixture.released.get())
        }
    }

    @Test fun aFailingObserverDoesNotDisableLaterPlaybackEvents() {
        Fixture().use { fixture ->
            val ended = java.util.concurrent.CountDownLatch(1)
            fixture.start(onEnded = ended::countDown)
            fixture.player.observePlaybackState { state ->
                if (state.positionSeconds > 0) error("UI observer failed")
            }
            fixture.send("""{"event":"property-change","name":"time-pos","data":3}""")
            fixture.send("""{"event":"end-file","reason":"eof"}""")
            assertTrue("Observer failure killed the IPC event reader", ended.await(3, TimeUnit.SECONDS))
        }
    }

    @Test fun replacingPlaybackReusesTheQuietSocketAndReleasesThePreviousRelay() {
        Fixture().use { fixture ->
            fixture.start()
            fixture.awaitReader()
            assertTrue(fixture.request {
                fixture.player.play("https://example.test/second.mp4", displayName = "第 2 集").started
            })
            assertEquals(listOf("set_property", "force-media-title", "第 2 集"), fixture.readCommand())
            assertEquals(listOf("loadfile", "https://example.test/second.mp4", "replace"), fixture.readCommand())
            assertEquals(listOf("set_property", "pause", "false"), fixture.readCommand())
            assertEquals(listOf("set_property", "speed", "1.0"), fixture.readCommand())
            assertEquals(listOf("set_property", "fullscreen", "no"), fixture.readCommand())
            assertEquals(1, fixture.released.get())
        }
    }

    @Test fun queuedRelaysRemainAliveUntilThePlayerCloses() {
        Fixture().use { fixture ->
            fixture.start()
            assertTrue(fixture.request { fixture.player.enqueue("https://example.test/next.mp4").started })
            assertEquals(listOf("loadfile", "https://example.test/next.mp4", "append"), fixture.readCommand())
            assertEquals(0, fixture.released.get())
            fixture.peer.close()
            fixture.request { fixture.player.stop() }
            assertEquals(2, fixture.released.get())
        }
    }

    @Test fun aDisconnectedSocketRejectsControlsWithoutLeavingAStaleWriter() {
        Fixture().use { fixture ->
            fixture.start()
            fixture.peer.close()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (fixture.request { fixture.player.togglePause() } && System.nanoTime() < deadline) Thread.sleep(10)
            assertFalse(fixture.request { fixture.player.togglePause() })
            assertTrue("The still-running player must remain tracked", fixture.player.isRunning())
            assertEquals(0, fixture.released.get())
        }
    }

    @Test fun realMpvPlaysALocalFileWithSpacesAndAcceptsPlaybackControls() {
        val executable = Path.of("/usr/bin/mpv")
        org.junit.Assume.assumeTrue("mpv required for integration test", Files.isExecutable(executable))
        val directory = Files.createTempDirectory("mpv local playback ")
        val file = directory.resolve("第 1 集.wav")
        val format = javax.sound.sampled.AudioFormat(8000f, 16, 1, true, false)
        javax.sound.sampled.AudioInputStream(ByteArrayInputStream(ByteArray(8000 * 2 * 20)), format, 160000).use {
            javax.sound.sampled.AudioSystem.write(it, javax.sound.sampled.AudioFileFormat.Type.WAVE, file.toFile())
        }
        val state = java.util.concurrent.atomic.AtomicReference(PlaybackState())
        val player = ExternalPlayerAdapter(executable, { command ->
            ProcessBuilder(command.take(1) + listOf("--no-config", "--load-scripts=no", "--ao=null", "--vo=null") + command.drop(1))
                .redirectErrorStream(true).start()
        }, PlaybackTargetPreparer { target, _ -> PreparedPlaybackTarget(target) }, singleWindow = { true })
        try {
            assertTrue(player.play(file.toString()).started)
            await { player.observePlaybackState { state.set(it) } && state.get().durationSeconds > 0 }
            assertTrue(player.togglePause())
            await { state.get().paused }
            assertTrue(player.setSpeed(1.5))
            await { state.get().speed == 1.5 }
            assertTrue(player.seekTo(3.0))
            await { state.get().positionSeconds >= 2.9 }
            assertTrue(player.stop())
            assertFalse(player.isRunning())
        } finally { player.terminateAll(); directory.toFile().deleteRecursively() }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue("mpv state update timed out", condition())
    }

    private class Fixture : AutoCloseable {
        private val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        private val process = FixtureProcess()
        private val workers = Executors.newCachedThreadPool()
        private var socket: Path? = null
        lateinit var peer: SocketChannel
        private lateinit var reader: java.io.BufferedReader
        val released = AtomicInteger()
        val player = ExternalPlayerAdapter(Path.of("/usr/bin/mpv"), { command ->
            socket = Path.of(command.single { it.startsWith("--input-ipc-server=") }.substringAfter('='))
            server.bind(UnixDomainSocketAddress.of(socket))
            process
        }, PlaybackTargetPreparer { target, _ ->
            PreparedPlaybackTarget(target, AutoCloseable { released.incrementAndGet() })
        }, singleWindow = { true })

        fun start(onEnded: (() -> Unit)? = null) {
            assertTrue(player.play("https://example.test/video.mp4", onEnded = onEnded).started)
            peer = workers.submit<SocketChannel> { server.accept() }.get(3, TimeUnit.SECONDS)
            reader = java.nio.channels.Channels.newReader(peer, Charsets.UTF_8).buffered()
            repeat(6) { assertEquals("observe_property", readCommand()[0]) }
        }

        fun awaitReader() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (System.nanoTime() < deadline) {
                if (Thread.getAllStackTraces().any { (thread, stack) ->
                    thread.name == "tvbox-mpv-ipc-${process.pid()}" && stack.any { it.methodName == "readLine" }
                }) return
                Thread.sleep(10)
            }
            fail("IPC reader never waited for an event")
        }

        fun request(action: () -> Boolean): Boolean = workers.submit<Boolean> { action() }.get(3, TimeUnit.SECONDS)

        fun readCommand(): List<String> = workers.submit<List<String>> {
            val line = reader.readLine()
            assertFalse("JSON must escape control characters", line.any(Character::isISOControl))
            JsonParser.parseString(line).asJsonObject.getAsJsonArray("command").map { it.asString }
        }.get(3, TimeUnit.SECONDS)

        fun send(event: String) {
            val bytes = ByteBuffer.wrap((event + "\n").toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) peer.write(bytes)
        }

        override fun close() {
            if (::peer.isInitialized) peer.close()
            server.close()
            process.destroy()
            player.terminateAll()
            workers.shutdownNow()
            socket?.let { Files.deleteIfExists(it) }
        }
    }

    private class FixtureProcess : Process() {
        private val exit = CompletableFuture<Process>()
        @Volatile private var alive = true
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream() = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int { exit.get(); return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !alive
        override fun exitValue() = 0
        override fun destroy() { alive = false; exit.complete(this) }
        override fun destroyForcibly(): Process { destroy(); return this }
        override fun isAlive() = alive
        override fun pid() = 987654321L
        override fun onExit(): CompletableFuture<Process> = exit
    }
}
