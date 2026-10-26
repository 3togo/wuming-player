package com.wuming.tv.desktop.player

import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalPlayerAdapterTest {
    @Test
    fun removedSdkProtocolsNeverLaunchAnExternalProcess() {
        org.junit.Assume.assumeFalse(com.wuming.tv.core.base.EditionSettings.current.nativeProtocols)
        val player = ExternalPlayerAdapter(Path.of("/nonexistent/wuming-test-player"))
        for (url in listOf("tvbus://channel", "jianpian://item", "magnet:?xt=example", "p2p://channel", "https://example.test/file.torrent")) {
            val result = player.play(url)
            assertFalse(result.started)
            assertTrue(result.message.contains("protocol is not supported"))
            assertFalse(player.enqueue(url).started)
        }
    }

    @Test
    fun incrementalPropertiesPreserveTheWholePlaybackSnapshot() {
        var state = PlaybackState()
        listOf("duration" to "3600", "time-pos" to "90", "speed" to "1.5",
            "pause" to "true", "paused-for-cache" to "true").forEach { (name, value) ->
            state = requireNotNull(parseMpvPropertyChange("""{"event":"property-change","name":"$name","data":$value}""", state))
        }
        assertEquals(PlaybackState(true, 90.0, 3600.0, 1.5, true), state)
        assertEquals(state, parseMpvPropertyChange("""{"event":"property-change","name":"time-pos","data":-4}""", state))
        assertNull(parseMpvPropertyChange("""{"event":"property-change","name":"duration","data":null}""", state))
        assertNull(parseMpvPropertyChange("broken", state))
    }

    @Test
    fun savedPreferencesBecomeMpvLaunchArgumentsOnly() {
        val mpv = ExternalPlayerAdapter(Path.of("/usr/bin/mpv"))
        val args = mpv.command("https://example.test/a", emptyMap(), initialSpeed = 1.5, fullscreen = true)
        assertTrue("--speed=1.5" in args)
        assertTrue("--fullscreen" in args)
        assertFalse(mpv.command("https://example.test/a", emptyMap(), initialSpeed = Double.NaN).any { it.startsWith("--speed=") })
        val vlc = ExternalPlayerAdapter(Path.of("/usr/bin/vlc"))
        assertFalse(vlc.command("https://example.test/a", emptyMap(), initialSpeed = 1.5, fullscreen = true).any { it.startsWith("--speed=") || it == "--fullscreen" })
    }

    @Test
    fun mpvAvoidsTheUnscaledNativeTitleBarButKeepsWindowDragging() {
        val args = ExternalPlayerAdapter(Path.of("/usr/bin/mpv"))
            .command("https://example.test/video.mp4", emptyMap())

        assertTrue("--title-bar=no" in args)
        assertTrue("--border=no" in args)
        assertTrue("--window-dragging=yes" in args)
        assertTrue("--osc=yes" in args)
    }

    @Test
    fun startupEventRequiresPlaybackRestartRatherThanFileOpen() {
        assertTrue(isMpvPlaybackStartedEvent("""{"event":"playback-restart"}"""))
        assertFalse(isMpvPlaybackStartedEvent("""{"event":"file-loaded"}"""))
        assertFalse(isMpvPlaybackStartedEvent("""{"event":"end-file","reason":"error"}"""))
        assertFalse(isMpvPlaybackStartedEvent("malformed"))
    }


    @Test
    fun mpvIpcReportsStartupAndFailureOnce() {
        val process = AliveProcess()
        val started = java.util.concurrent.atomic.AtomicInteger()
        val failed = java.util.concurrent.atomic.AtomicInteger()
        val completed = CountDownLatch(1)
        val server = java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)
        var socket: Path? = null
        val adapter = ExternalPlayerAdapter(Path.of("/usr/bin/mpv"), { command ->
            socket = Path.of(command.single { it.startsWith("--input-ipc-server=") }.substringAfter('='))
            server.bind(java.net.UnixDomainSocketAddress.of(socket))
            process
        }, PlaybackTargetPreparer { target, _ -> PreparedPlaybackTarget(target) })
        try {
            assertTrue(adapter.play("https://example.test/video.mp4",
                onPlaybackStarted = { started.incrementAndGet() },
                onPlaybackFailed = { failed.incrementAndGet(); completed.countDown() },
            ).started)
            server.configureBlocking(false)
            var connection: java.nio.channels.SocketChannel? = null
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (connection == null && System.nanoTime() < deadline) {
                connection = server.accept()
                if (connection == null) Thread.sleep(10)
            }
            checkNotNull(connection).use { client ->
                val events = listOf(
                    """{"event":"file-loaded"}""",
                    """{"event":"playback-restart"}""",
                    """{"event":"playback-restart"}""",
                    """{"event":"end-file","reason":"error"}""",
                ).joinToString("\n", postfix = "\n")
                val bytes = java.nio.ByteBuffer.wrap(events.toByteArray())
                while (bytes.hasRemaining()) client.write(bytes)
                assertTrue(completed.await(3, TimeUnit.SECONDS))
                assertEquals(1, started.get())
                assertEquals(1, failed.get())
            }
        } finally {
            adapter.stop()
            server.close()
            socket?.let { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun acceptsWebUrlsAndRejectsUnsupportedSchemes() {
        val adapter = ExternalPlayerAdapter(Path.of("/usr/bin/mpv"))

        assertEquals("https://media.example/video.mkv", adapter.normalizeTarget("https://media.example/video.mkv"))
        assertNull(adapter.normalizeTarget("javascript:alert(1)"))
        assertNull(adapter.normalizeTarget("https:///missing-host"))
    }

    @Test
    fun sendsTargetAsASeparateProcessArgument() {
        var command = emptyList<String>()
        val adapter = ExternalPlayerAdapter(
            Path.of("/opt/player"),
            { command = it; null },
            PlaybackTargetPreparer { target, _ -> PreparedPlaybackTarget(target) },
        )

        val result = adapter.play("https://media.example/video.mkv?token=value")

        assertTrue(result.started)
        assertEquals(listOf("/opt/player", "https://media.example/video.mkv?token=value"), command)
    }

    @Test
    fun acceptsExistingLocalFilesOnly() {
        val file = Files.createTempFile("tvbox-desktop-", ".mkv")
        try {
            val adapter = ExternalPlayerAdapter(Path.of("/opt/player"))

            assertEquals(file.toUri().toString(), adapter.normalizeTarget(file.toString()))
            assertFalse(adapter.play(file.resolveSibling("missing.mkv").toString()).started)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun passesLiveHeadersAsSafePlayerArguments() {
        val mpv = ExternalPlayerAdapter(Path.of("/usr/bin/mpv"))
        val vlc = ExternalPlayerAdapter(Path.of("/usr/bin/vlc"))
        val headers = linkedMapOf("User-Agent" to "TVBox Desktop", "Referer" to "https://portal.example/")

        assertEquals(
            listOf(
                "/usr/bin/mpv",
                "--title-bar=no",
                "--border=no",
                "--window-dragging=yes",
                "--osc=yes",
                "--user-agent=TVBox Desktop",
                "--http-header-fields=Referer: https://portal.example/",
                "https://media.example/live.m3u8",
            ),
            mpv.command("https://media.example/live.m3u8", headers),
        )
        assertEquals(
            listOf(
                "/usr/bin/vlc",
                "--no-one-instance",
                "--no-playlist-enqueue",
                "--play-and-exit",
                "--no-qt-start-minimized",
                "--video-on-top",
                "--verbose=2",
                "--http-user-agent=TVBox Desktop",
                "--http-referrer=https://portal.example/",
                "https://media.example/live.m3u8",
            ),
            vlc.command("https://media.example/live.m3u8", headers),
        )
    }

    @Test
    fun preservesCommasInBrowserUserAgentAndOtherHeaders() {
        val adapter = ExternalPlayerAdapter(Path.of("/usr/bin/mpv"))
        val ua = "Mozilla/5.0 (KHTML, like Gecko)"
        val command = adapter.command("https://media.example/video.m3u8", linkedMapOf(
            "user-agent" to ua, "X-Test" to "one,two\\three", "Referer" to "https://portal.example/a,b",
        ))
        assertTrue(command.contains("--user-agent=$ua"))
        assertEquals(
            "--http-header-fields=X-Test: one\\,two\\\\three,Referer: https://portal.example/a\\,b",
            command.single { it.startsWith("--http-header-fields=") },
        )
    }

    @Test
    fun managedMpvCommandEnablesIdleJsonIpc() {
        val adapter = ExternalPlayerAdapter(Path.of("/usr/bin/mpv"))

        assertEquals(
            listOf(
                "/usr/bin/mpv",
                "--title-bar=no",
                "--border=no",
                "--window-dragging=yes",
                "--osc=yes",
                "--idle=yes",
                "--keep-open=yes",
                "--keep-open-pause=no",
                "--force-window=yes",
                "--input-ipc-server=/tmp/tvbox-test.sock",
                "--force-media-title=Example Show — Episode 2",
                "--user-agent=TVBox Desktop",
                "https://media.example/episode.m3u8",
            ),
            adapter.command(
                "https://media.example/episode.m3u8",
                mapOf("User-Agent" to "TVBox Desktop"),
                Path.of("/tmp/tvbox-test.sock"),
                "Example Show — Episode 2",
            ),
        )
    }

    @Test
    fun recognizesOnlyNaturalMpvEndEvents() {
        assertTrue(isMpvNaturalEndEvent("""{"event":"end-file","reason":"eof"}"""))
        assertFalse(isMpvNaturalEndEvent("""{"event":"end-file","reason":"stop"}"""))
        assertFalse(isMpvNaturalEndEvent("not-json"))
    }

    @Test
    fun recognizesMpvEofReachedPropertyWhileKeepOpenIsActive() {
        assertTrue(isMpvEofReachedEvent(
            """{"event":"property-change","id":1,"name":"eof-reached","data":true}""",
        ))
        assertFalse(isMpvEofReachedEvent(
            """{"event":"property-change","id":1,"name":"eof-reached","data":false}""",
        ))
        assertFalse(isMpvEofReachedEvent("""{"event":"property-change","name":"pause","data":true}"""))
    }

    @Test
    fun suppliesAndroidTvCompatibleUserAgentWhenProviderOmitsOne() {
        var command = emptyList<String>()
        val adapter = ExternalPlayerAdapter(
            Path.of("/usr/bin/vlc"),
            { command = it; null },
            PlaybackTargetPreparer { target, _ -> PreparedPlaybackTarget(target) },
        )

        val result = adapter.play("https://media.example/video.m3u8")

        assertTrue(result.started)
        assertEquals(
            listOf(
                "/usr/bin/vlc",
                "--no-one-instance",
                "--no-playlist-enqueue",
                "--play-and-exit",
                "--no-qt-start-minimized",
                "--video-on-top",
                "--verbose=2",
                "--http-user-agent=${ExternalPlayerAdapter.DEFAULT_PLAYBACK_USER_AGENT}",
                "https://media.example/video.m3u8",
            ),
            command,
        )
    }

    @Test
    fun preservesProviderUserAgent() {
        var command = emptyList<String>()
        val adapter = ExternalPlayerAdapter(
            Path.of("/usr/bin/vlc"),
            { command = it; null },
            PlaybackTargetPreparer { target, _ -> PreparedPlaybackTarget(target) },
        )

        adapter.play(
            "https://media.example/video.m3u8",
            mapOf("user-agent" to "Provider UA"),
        )

        assertEquals(1, command.count { it.startsWith("--http-user-agent=") })
        assertTrue(command.contains("--http-user-agent=Provider UA"))
    }

    @Test
    fun reportsExpectedTransportRejectionWithoutStartingPlayer() {
        var started = false
        val adapter = ExternalPlayerAdapter(
            Path.of("/usr/bin/vlc"),
            { started = true; null },
            PlaybackTargetPreparer { _, _ ->
                throw PlaybackPreparationException("Media server rejected the HLS manifest (HTTP 403).")
            },
        )

        val result = adapter.play("https://media.example/video.m3u8")

        assertFalse(started)
        assertFalse(result.started)
        assertEquals("Media server rejected the HLS manifest (HTTP 403).", result.message)
    }

    @Test
    fun classifiesUsefulPlayerDiagnosticsWithoutReturningTheMediaUrl() {
        assertEquals(
            "first-video-frame",
            classifyPlayerOutput("decoder debug: Received first picture for https://secret.example/token"),
        )
        assertEquals("http-status-403", classifyPlayerOutput("access error: HTTP answer code 403"))
        assertEquals("http-status-400", classifyPlayerOutput("[ffmpeg] https: HTTP error 400 Bad Request"))
        assertNull(classifyPlayerOutput("access error: HTTP answer code 302"))
        assertNull(classifyPlayerOutput("audio output error: PulseAudio server connection failure"))
        assertNull(classifyPlayerOutput("playlist item https://secret.example/token"))
    }

    @Test
    fun invokesEndCallbackOnlyForNaturalPlayerCompletion() {
        val ended = CountDownLatch(1)
        val adapter = ExternalPlayerAdapter(
            Path.of("/usr/bin/vlc"),
            { FinishedProcess("main playlist: end of playlist, exiting\n") },
            PlaybackTargetPreparer { target, _ -> PreparedPlaybackTarget(target) },
        )

        adapter.play("https://media.example/episode.m3u8", onEnded = ended::countDown)

        assertTrue(ended.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun doesNotInvokeEndCallbackWhenPlayerIsClosedWithoutEndSignal() {
        val ended = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val adapter = ExternalPlayerAdapter(
            Path.of("/usr/bin/vlc"),
            { FinishedProcess("main playlist debug: user requested stop\n") },
            PlaybackTargetPreparer { target, _ -> PreparedPlaybackTarget(target) },
        )

        adapter.play(
            "https://media.example/episode.m3u8",
            onEnded = ended::countDown,
            onClosed = closed::countDown,
        )

        assertFalse(ended.await(100, TimeUnit.MILLISECONDS))
        assertTrue(closed.await(1, TimeUnit.SECONDS))
    }

    private class FinishedProcess(output: String) : Process() {
        private val input = ByteArrayInputStream(output.toByteArray())

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream() = input
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        override fun waitFor() = 0
        override fun waitFor(timeout: Long, unit: TimeUnit) = true
        override fun exitValue() = 0
        override fun destroy() = Unit
        override fun isAlive() = false
        override fun pid() = 4_242L
        override fun onExit(): CompletableFuture<Process> = CompletableFuture.completedFuture(this)
    }

    @Test
    fun oneWindowStopsThePreviousPlayerBeforeOpeningAnother() {
        val first = AliveProcess()
        val second = AliveProcess()
        var launches = 0
        val adapter = ExternalPlayerAdapter(Path.of("/usr/bin/vlc"), {
            if (launches++ == 0) first else {
                assertFalse("Previous player must exit before replacement", first.isAlive)
                second
            }
        }, PlaybackTargetPreparer { target, _ -> PreparedPlaybackTarget(target) }, singleWindow = { true })
        try {
            assertTrue(adapter.play("https://media.example/first.mp4").started)
            assertTrue(adapter.play("https://media.example/second.mp4").started)
            assertEquals(1, first.destroyCount)
            assertTrue(second.isAlive)
        } finally { adapter.terminateAll() }
    }

    @Test
    fun concurrentPlaybackRequestsStillOpenOnlyOnePlayerAtATime() {
        val ready = java.util.concurrent.CountDownLatch(2)
        val players = java.util.concurrent.CopyOnWriteArrayList<AliveProcess>()
        val adapter = ExternalPlayerAdapter(Path.of("/usr/bin/vlc"), {
            assertTrue("Player launches must be serialized", players.none { it.isAlive })
            AliveProcess().also { players += it }
        }, PlaybackTargetPreparer { target, _ ->
            ready.countDown()
            check(ready.await(5, TimeUnit.SECONDS))
            PreparedPlaybackTarget(target)
        }, singleWindow = { true })
        val workers = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val requests = (1..2).map { index -> workers.submit<PlayerLaunchResult> {
                adapter.play("https://media.example/$index.mp4")
            } }
            assertTrue(requests.all { it.get(5, TimeUnit.SECONDS).started })
            assertEquals(2, players.size)
            assertEquals(1, players.count { it.isAlive })
        } finally { adapter.terminateAll(); workers.shutdownNow() }
    }

    @Test
    fun terminateAllKillsEveryPlayerItStarted() {
        val first = AliveProcess()
        val second = AliveProcess()
        var launches = 0
        val adapter = ExternalPlayerAdapter(
            Path.of("/usr/bin/vlc"),
            { if (launches++ == 0) first else second },
            PlaybackTargetPreparer { target, _ -> PreparedPlaybackTarget(target) },
            singleWindow = { false },
        )

        adapter.play("https://media.example/one.m3u8")
        adapter.play("https://media.example/two.m3u8")

        assertTrue(adapter.isRunning())
        assertTrue(adapter.terminateAll())

        assertEquals(1, first.destroyCount)
        assertEquals(1, second.destroyCount)
        assertFalse(adapter.isRunning())
    }

    @Test
    fun terminateAllIsSafeWhenNothingWasStarted() {
        val adapter = ExternalPlayerAdapter(Path.of("/usr/bin/vlc"))

        assertFalse(adapter.terminateAll())
        assertFalse(adapter.isRunning())
        assertFalse(adapter.stop())
        assertFalse(adapter.next())
        assertFalse(adapter.previous())
    }

    @Test
    fun `seekTo returns false without a running mpv session`() {
        val adapter = ExternalPlayerAdapter(Path.of("/usr/bin/mpv"))
        assertFalse(adapter.seekTo(30.0))
    }

    @Test
    fun `setSpeed returns false without a running mpv session`() {
        val adapter = ExternalPlayerAdapter(Path.of("/usr/bin/mpv"))
        assertFalse(adapter.setSpeed(1.5))
    }

    @Test
    fun `addSubtitle returns false without a running mpv session`() {
        val adapter = ExternalPlayerAdapter(Path.of("/usr/bin/mpv"))
        assertFalse(adapter.addSubtitle("/tmp/sub.srt"))
    }

    @Test
    fun `togglePause returns false without a running mpv session`() {
        val adapter = ExternalPlayerAdapter(Path.of("/usr/bin/mpv"))
        assertFalse(adapter.togglePause())
    }

    @Test
    fun `observePlaybackState returns false without a running session`() {
        val adapter = ExternalPlayerAdapter(Path.of("/usr/bin/mpv"))
        assertFalse(adapter.observePlaybackState(PlaybackStateCallback { }))
    }

    @Test
    fun `seekTo returns false for vlc even if running`() {
        val adapter = ExternalPlayerAdapter(Path.of("/usr/bin/vlc"))
        assertFalse(adapter.seekTo(10.0))
    }

    @Test
    fun `setSpeed returns false for vlc even if running`() {
        val adapter = ExternalPlayerAdapter(Path.of("/usr/bin/vlc"))
        assertFalse(adapter.setSpeed(2.0))
    }

    @Test
    fun `parseMpvPropertyChange extracts time-pos`() {
        val state = parseMpvPropertyChange(
            """{"event":"property-change","id":2,"name":"time-pos","data":42.5}""",
        )
        assertEquals(42.5, state?.positionSeconds ?: 0.0, 0.01)
    }

    @Test
    fun `parseMpvPropertyChange extracts duration`() {
        val state = parseMpvPropertyChange(
            """{"event":"property-change","id":3,"name":"duration","data":3600.0}""",
        )
        assertEquals(3600.0, state?.durationSeconds ?: 0.0, 0.01)
    }

    @Test
    fun `parseMpvPropertyChange extracts pause state`() {
        val state = parseMpvPropertyChange(
            """{"event":"property-change","id":4,"name":"pause","data":true}""",
        )
        assertTrue(state?.paused == true)
    }

    @Test
    fun `parseMpvPropertyChange extracts speed`() {
        val state = parseMpvPropertyChange(
            """{"event":"property-change","id":5,"name":"speed","data":1.5}""",
        )
        assertEquals(1.5, state?.speed ?: 0.0, 0.01)
    }

    @Test
    fun `parseMpvPropertyChange extracts buffering state`() {
        val state = parseMpvPropertyChange(
            """{"event":"property-change","id":6,"name":"paused-for-cache","data":true}""",
        )
        assertTrue(state?.buffering == true)
    }

    @Test
    fun `parseMpvPropertyChange returns null for non-property-change events`() {
        assertNull(parseMpvPropertyChange("""{"event":"end-file","reason":"eof"}"""))
        assertNull(parseMpvPropertyChange("not-json"))
    }

    @Test
    fun `parseMpvPropertyChange returns null for unknown properties`() {
        assertNull(parseMpvPropertyChange(
            """{"event":"property-change","id":99,"name":"volume","data":50}""",
        ))
    }

    private class AliveProcess : Process() {
        var destroyCount = 0
            private set
        @Volatile private var alive = true

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream() = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int { while (alive) Thread.onSpinWait(); return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit) = true
        override fun exitValue() = 0
        override fun destroy() { destroyCount++; alive = false }
        override fun isAlive() = alive
        override fun pid() = 4_243L
        override fun onExit(): CompletableFuture<Process> = CompletableFuture()
    }
}
