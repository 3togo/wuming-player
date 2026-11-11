package com.wuming.tv.desktop.player

import com.wuming.tv.core.base.Digests
import com.wuming.tv.core.playback.HlsAdRule
import java.net.URI
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

data class PlayerLaunchResult(val started: Boolean, val message: String)

fun interface ProcessStarter {
    fun start(command: List<String>): Process?
}

fun interface PlaybackStateCallback {
    fun onState(state: PlaybackState)
}

typealias PlaybackState = com.wuming.tv.core.playback.PlaybackState

/** Host-owned native video surface. Implementations marshal UI work to the event thread. */
interface EmbeddedVideoHost {
    fun acquire(title: String?, fullscreen: Boolean): Long?
    fun externalPlayback()
    fun playbackClosed()
}

class ExternalPlayerAdapter internal constructor(
    val executable: Path,
    private val processStarter: ProcessStarter,
    private val targetPreparer: PlaybackTargetPreparer = HlsCompatibilityPreparer(),
    private val singleWindow: () -> Boolean = { com.wuming.tv.desktop.config.DesktopApplicationSettings().preventMultiplePlayerWindows },
    private val mpvConfiguration: com.wuming.tv.desktop.config.DesktopMpvConfiguration = com.wuming.tv.desktop.config.DesktopMpvConfiguration(),
    private val subtitleLocale: () -> java.util.Locale = java.util.Locale::getDefault,
) {
    constructor(
        executable: Path,
        processStarter: ProcessStarter = ProcessStarter { command ->
            playerProcessBuilder(command).redirectErrorStream(true).start()
        },
    ) : this(executable, processStarter, HlsCompatibilityPreparer())

    @Volatile private var volumeOverride: Int? = null
    @Volatile private var muteOverride: Boolean? = null
    @Volatile private var embeddedHost: EmbeddedVideoHost? = null
    private val stateListeners = java.util.concurrent.CopyOnWriteArrayList<PlaybackStateCallback>()
    fun addPlaybackStateListener(callback: PlaybackStateCallback): AutoCloseable {
        stateListeners += callback
        return AutoCloseable { stateListeners -= callback }
    }

    private val stopGeneration = java.util.concurrent.atomic.AtomicLong()
    private val playbackRequest = java.util.concurrent.atomic.AtomicLong()

    fun setEmbeddedVideoHost(host: EmbeddedVideoHost?) { embeddedHost = host }

    fun openExternally(): Boolean {
        val session = synchronized(mpvSessionLock) { mpvSession } ?: return false
        return session.retryExternally?.invoke() ?: false
    }

    fun setVolume(percent: Int): Boolean {
        if (percent !in 0..100) return false
        volumeOverride = percent
        return mpvSetProperty("volume", percent.toString())
    }

    fun setMuted(muted: Boolean): Boolean {
        muteOverride = muted
        return mpvSetProperty("mute", if (muted) "yes" else "no")
    }

    fun seekRelative(seconds: Double): Boolean = seconds.isFinite() && mpvCommand("seek", seconds.toString(), "relative")

    private val logger = Logger.getLogger("tvbox.player")
    private val mpvSessionLock = Any()
    private val windowLaunchLock = Any()
    private var mpvSession: MpvSession? = null
    private val launchedLock = Any()
    private val launched = LinkedHashSet<Process>()
    val name: String = executable.fileName.toString()

    fun configureHlsAdRules(rules: List<HlsAdRule>) {
        (targetPreparer as? HlsCompatibilityPreparer)?.updateAdRules(rules)
    }

    fun seekTo(positionSeconds: Double): Boolean {
        if (!name.equals("mpv", true)) return false
        return mpvCommand("seek", positionSeconds.toString(), "absolute")
    }

    fun setSpeed(speed: Double): Boolean {
        if (!name.equals("mpv", true)) return false
        return mpvSetProperty("speed", speed.toString())
    }

    fun addSubtitle(filePath: String): Boolean {
        if (!name.equals("mpv", true)) return false
        // Explicit user selection must activate the track; mpv auto only loads it.
        val added = mpvCommand("sub-add", filePath, "cached")
        if (added) synchronized(mpvSessionLock) { mpvSession?.subtitles?.add(filePath) }
        return added
    }

    fun togglePause(): Boolean {
        if (!name.equals("mpv", true)) return false
        return mpvCommand("cycle", "pause")
    }

    fun observePlaybackState(callback: PlaybackStateCallback): Boolean {
        val session = synchronized(mpvSessionLock) { mpvSession } ?: return false
        session.stateCallback = callback
        callback.onState(session.playbackState)
        return true
    }

    fun play(
        value: String,
        headers: Map<String, String> = emptyMap(),
        onEnded: (() -> Unit)? = null,
        onClosed: (() -> Unit)? = null,
        reuseRunningPlayer: Boolean = false,
        displayName: String? = null,
        onPlaybackStarted: (() -> Unit)? = null,
        onPlaybackFailed: ((String) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
        initialSpeed: Double = 1.0,
        fullscreen: Boolean = false,
    ): PlayerLaunchResult {
        val request = playbackRequest.incrementAndGet()
        val stopped = stopGeneration.get()
        return playInternal(value, headers, onEnded, onClosed, reuseRunningPlayer, displayName,
            onPlaybackStarted, onPlaybackFailed, { isCancelled() || stopped != stopGeneration.get() }, initialSpeed, fullscreen,
            retryAllowed = { request == playbackRequest.get() })
    }

    private fun playInternal(
        value: String,
        headers: Map<String, String>,
        onEnded: (() -> Unit)? = null,
        onClosed: (() -> Unit)? = null,
        reuseRunningPlayer: Boolean = false,
        displayName: String? = null,
        onPlaybackStarted: (() -> Unit)? = null,
        onPlaybackFailed: ((String) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
        initialSpeed: Double = 1.0,
        fullscreen: Boolean = false,
        forceExternal: Boolean = false,
        retryAllowed: () -> Boolean = { true },
    ): PlayerLaunchResult {
        if (com.wuming.tv.core.source.PlaybackProtocolPolicy.unsupported(value)) {
            return PlayerLaunchResult(false, "This playback protocol is not supported in this build. Choose another source.")
        }
        val resolved = resolve(value, headers)
            ?: return PlayerLaunchResult(false, "Enter an HTTP(S), file URL, or existing local file.")
        val target = resolved.target
        val safeHeaders = resolved.safeHeaders
        var prepared: PreparedPlaybackTarget? = null
        var embeddedAttempt = false
        return try {
            if (isCancelled()) return PlayerLaunchResult(false, "Playback cancelled.")
            prepared = targetPreparer.prepare(target, safeHeaders)
            if (isCancelled()) {
                prepared.resource?.close()
                return PlayerLaunchResult(false, "Playback cancelled.")
            }
            val ready = prepared
            val host = embeddedHost
            val windowId = if (!forceExternal && name.equals("mpv", true)) host?.acquire(displayName, fullscreen)?.takeIf { it > 0 } else null
            embeddedAttempt = windowId != null
            if (windowId == null) host?.externalPlayback()
            synchronized(windowLaunchLock) {
                if (isCancelled()) {
                    ready.resource?.close()
                    return PlayerLaunchResult(false, "Playback cancelled.")
                }
                val keepOneWindow = singleWindow()
                if (windowId == null && (reuseRunningPlayer || keepOneWindow) && name.equals("mpv", true) &&
                    enqueueInMpvSession(ready, safeHeaders, onEnded, onClosed, displayName, onPlaybackStarted = onPlaybackStarted, onPlaybackFailed = onPlaybackFailed, windowId = windowId)
                ) {
                    if (initialSpeed.isFinite() && initialSpeed in 0.25..4.0) setSpeed(initialSpeed)
                    mpvSetProperty("fullscreen", if (fullscreen) "yes" else "no")
                    logger.info("reuse player=$name target=${tag(target)} compatibilityRelay=${ready.resource != null}")
                    return PlayerLaunchResult(true, "Playing in $name")
                }
                if (keepOneWindow || windowId != null || (forceExternal && synchronized(mpvSessionLock) { mpvSession?.windowId != null })) stopBeforeReplacement()
                if (isCancelled()) {
                    ready.resource?.close()
                    return PlayerLaunchResult(false, "Playback cancelled.")
                }
                val ipcPath = if (name.equals("mpv", true)) newMpvSocketPath() else null
                val process = processStarter.start(command(ready.location, safeHeaders, ipcPath, displayName, initialSpeed, fullscreen, windowId))
                logger.info(
                    "launch player=$name target=${tag(target)} headers=${safeHeaders.keys.sorted()} " +
                        "compatibilityRelay=${ready.resource != null} pid=${process?.pid() ?: "test"}",
                )
                process?.let { running ->
                    track(running)
                    val session = ipcPath?.let { path ->
                        MpvSession(running, path, transportHeaders(safeHeaders), ready, onEnded, onClosed, onPlaybackStarted = onPlaybackStarted, onPlaybackFailed = onPlaybackFailed, windowId = windowId).also {
                            val item = it
                            if (windowId != null) item.retryExternally = {
                                val retry = synchronized(mpvSessionLock) {
                                    if (mpvSession !== item || isCancelled() || !retryAllowed() || !item.retryStarted.compareAndSet(false, true)) null
                                    else {
                                        val callbacks = Triple(item.onEnded, item.onClosed, item.onPlaybackStarted)
                                        item.onEnded = null; item.onClosed = null; item.onPlaybackStarted = null; item.onPlaybackFailed = null
                                        callbacks
                                    }
                                }
                                if (retry == null) false else {
                                    Thread({
                                        val snapshot = item.playbackState
                                        val position = snapshot.positionSeconds
                                        val subtitles = synchronized(mpvSessionLock) { item.subtitles.toList() }
                                        val result = playInternal(value, headers, retry.first, retry.second, false, displayName,
                                            {
                                                if (position > 0) seekTo(position)
                                                subtitles.forEach(::addSubtitle)
                                                if (snapshot.paused) mpvSetProperty("pause", "yes")
                                                retry.third?.invoke()
                                            }, onPlaybackFailed, { isCancelled() || !retryAllowed() }, snapshot.speed, fullscreen,
                                            forceExternal = true, retryAllowed = retryAllowed)
                                        if (result.started) item.stateCallback?.let { observePlaybackState(it) }
                                        else if (!isCancelled()) { onPlaybackFailed?.invoke(result.message); retry.second?.invoke() }
                                    }, "tvbox-player-external-fallback").apply { isDaemon = true; start() }
                                    true
                                }
                            }
                            synchronized(mpvSessionLock) { mpvSession = item }
                            connectMpvSession(item)
                        }
                    }
                    val output = monitorOutput(running, session)
                    running.onExit().thenCombine(output) { finished, naturalEnd ->
                        logger.info(
                            "exit player=$name pid=${finished.pid()} code=${finished.exitValue()} " +
                                "naturalEnd=$naturalEnd target=${tag(target)}",
                        )
                        val retrying = session?.takeIf { it.windowId != null && !it.started && !it.mediaFailed && !isCancelled() }
                            ?.retryExternally?.invoke() == true
                        if (session != null) closeMpvSession(session) else ready.resource?.close()
                        if (retrying) return@thenCombine
                        if (session?.windowId != null && !session.retryStarted.get() && !isCancelled()) host?.playbackClosed()
                        if (session == null && naturalEnd && finished.exitValue() == 0) {
                            runCatching { onEnded?.invoke() }.onFailure { error ->
                                logger.log(Level.WARNING, "end callback failed player=$name", error)
                            }
                        }
                        val closed = if (session != null) session.onClosed else onClosed
                        runCatching { closed?.invoke() }.onFailure { error ->
                            logger.log(Level.WARNING, "close callback failed player=$name", error)
                        }
                    }
                }
                if (process == null) {
                    ready.resource?.close()
                    ipcPath?.let { Files.deleteIfExists(it) }
                }
                PlayerLaunchResult(true, "Opened in $name")
            }
        } catch (error: PlaybackPreparationException) {
            if (!isCancelled()) embeddedHost?.playbackClosed()
            prepared?.resource?.close()
            logger.warning("playback unavailable player=$name target=${tag(target)} error=${error.message}")
            PlayerLaunchResult(false, error.message ?: "Playback source is unavailable.")
        } catch (error: Exception) {
            prepared?.resource?.close()
            if (!forceExternal && embeddedAttempt && !isCancelled() && retryAllowed()) {
                return playInternal(value, headers, onEnded, onClosed, reuseRunningPlayer, displayName,
                    onPlaybackStarted, onPlaybackFailed, { isCancelled() || !retryAllowed() }, initialSpeed, fullscreen,
                    forceExternal = true, retryAllowed = retryAllowed)
            }
            logger.log(Level.WARNING, "launch failed player=$name target=${tag(target)} error=${error.message}", error)
            PlayerLaunchResult(false, "$name could not start: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    internal fun command(
        target: String,
        headers: Map<String, String>,
        mpvSocketPath: Path? = null,
        displayName: String? = null,
        initialSpeed: Double = 1.0,
        fullscreen: Boolean = false,
        windowId: Long? = null,
    ): List<String> {
        val arguments = mutableListOf(executable.toString())
        when (name.lowercase()) {
            "mpv" -> {
                // Update MpvConfigurationPolicy's diagnostic list when owned option names change.
                // Supply an automatic fallback first. User config may override it; app-owned
                // window/transport/session options below retain their required precedence.
                val languages = com.wuming.tv.core.media.subtitle.SubtitleSelectionPolicy.preferredLanguages(subtitleLocale())
                // mpv's -append adds one literal list item, so append each tag separately.
                languages.forEach { arguments += "--slang-append=$it" }
                arguments += mpvConfiguration.launchArguments()
                // The Linux window manager uses an unscaled 24px native caption on HiDPI screens,
                // clipping the media title. mpv's borderless window remains draggable and keeps
                // its scalable on-screen controller, fullscreen shortcut, and keyboard controls.
                arguments += listOf(
                    "--title-bar=no",
                    "--border=no",
                    "--window-dragging=yes",
                    "--osc=yes",
                )
                if (initialSpeed.isFinite() && initialSpeed in 0.25..4.0 && initialSpeed != 1.0) arguments += "--speed=$initialSpeed"
                if (windowId != null) {
                    require(windowId > 0) { "A positive native video window is required" }
                    arguments += listOf("--wid=$windowId", "--vo=gpu,x11", "--gpu-context=x11egl",
                        "--input-vo-keyboard=no", "--input-default-bindings=no", "--osc=no", "--window-dragging=no", "--fullscreen=no")
                } else if (fullscreen) arguments += "--fullscreen"
                mpvSocketPath?.let { path ->
                    arguments += listOf(
                        "--idle=yes",
                        "--keep-open=yes",
                        "--keep-open-pause=no",
                        "--force-window=yes",
                        "--input-ipc-server=$path",
                    )
                }
                displayName?.trim()?.takeIf(String::isNotEmpty)?.let { arguments += "--force-media-title=$it" }
                // A browser UA contains commas, which mpv's generic header list splits.
                // Use the dedicated option and escape separators in remaining headers.
                // Regression: "KHTML, like Gecko" caused HTTP 400 on Auete HLS segments;
                // probes without the launcher's default UA played and concealed this bug.
                headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
                    ?.let { arguments += "--user-agent=${it.value}" }
                val extraHeaders = headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) }
                if (extraHeaders.isNotEmpty()) {
                    arguments += "--http-header-fields=" + extraHeaders.entries.joinToString(",") {
                        "${it.key}: ${it.value}".replace("\\", "\\\\").replace(",", "\\,")
                    }
                }
                arguments += target
            }
            "vlc" -> {
                // A distro VLC commonly reuses an existing (possibly hidden) instance. Keep each
                // TVBox launch independent and visible so clicking a channel has deterministic UI.
                arguments += listOf(
                    "--no-one-instance",
                    "--no-playlist-enqueue",
                    "--play-and-exit",
                    "--no-qt-start-minimized",
                    "--video-on-top",
                    "--verbose=2",
                )
                headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
                    ?.let { arguments += "--http-user-agent=${it.value}" }
                headers.entries.firstOrNull { it.key.equals("Referer", ignoreCase = true) }
                    ?.let { arguments += "--http-referrer=${it.value}" }
                arguments += target
            }
            else -> arguments += target
        }
        return arguments
    }

    private fun enqueueInMpvSession(
        prepared: PreparedPlaybackTarget,
        headers: Map<String, String>,
        onEnded: (() -> Unit)?,
        onClosed: (() -> Unit)?,
        displayName: String?,
        mode: String = "replace",
        onPlaybackStarted: (() -> Unit)? = null,
        onPlaybackFailed: ((String) -> Unit)? = null,
        windowId: Long? = null,
    ): Boolean = synchronized(mpvSessionLock) {
        val session = mpvSession ?: return false
        if (!session.process.isAlive || session.writer == null || session.transportHeaders != transportHeaders(headers) || session.windowId != windowId) {
            return false
        }
        return try {
            displayName?.trim()?.takeIf(String::isNotEmpty)?.let { title ->
                session.writer?.apply {
                    write("""{"command":["set_property","force-media-title",${jsonString(title)}]}""")
                    newLine()
                    flush()
                }
            }
            val command = """{"command":["loadfile",${jsonString(prepared.location)},"$mode"]}"""
            session.writer?.apply {
                write(command)
                newLine()
                if (mode == "replace") {
                    write("""{"command":["set_property","pause",false]}""")
                    newLine()
                }
                flush()
            }
            if (mode == "replace") {
                val previous = session.prepared
                session.prepared = prepared
                session.playbackState = PlaybackState()
                session.started = false
                session.mediaFailed = false
                session.retryStarted.set(false)
                session.onEnded = onEnded
                session.onClosed = onClosed
                session.onPlaybackStarted = onPlaybackStarted
                session.onPlaybackFailed = onPlaybackFailed
                previous.resource?.close()
            } else {
                // Appended items keep their relay alive until mpv actually plays or drops them.
                session.queued += prepared
            }
            true
        } catch (error: Exception) {
            logger.fine("mpv IPC reuse failed pid=${session.process.pid()} error=${error.javaClass.simpleName}")
            false
        }
    }

    private fun connectMpvSession(session: MpvSession) {
        Thread({
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var channel: SocketChannel? = null
            while (session.process.isAlive && channel == null && System.nanoTime() < deadline) {
                channel = runCatching {
                    val attempt = SocketChannel.open(StandardProtocolFamily.UNIX)
                    try {
                        attempt.connect(UnixDomainSocketAddress.of(session.socketPath))
                        attempt
                    } catch (error: Exception) {
                        attempt.close()
                        throw error
                    }
                }.getOrNull()
                if (channel == null) Thread.sleep(25)
            }
            if (channel == null) {
                logger.warning("mpv IPC unavailable pid=${session.process.pid()}")
                if (session.windowId != null) session.retryExternally?.invoke()
                return@Thread
            }
            try {
                val reader = Channels.newReader(channel, Charsets.UTF_8)
                val writer = Channels.newWriter(channel, Charsets.UTF_8).buffered()
                synchronized(mpvSessionLock) {
                    if (mpvSession !== session) {
                        channel.close()
                        return@Thread
                    }
                    session.channel = channel
                    session.writer = writer
                    session.writer?.apply {
                        write("""{"command":["observe_property",1,"eof-reached"]}""")
                        newLine()
                        listOf("time-pos", "duration", "pause", "speed", "paused-for-cache").forEachIndexed { index, property ->
                            write("""{"command":["observe_property",${index + 2},"$property"]}""")
                            newLine()
                        }
                        flush()
                    }
                }
                volumeOverride?.let { mpvSetProperty("volume", it.toString()) }
                muteOverride?.let { mpvSetProperty("mute", if (it) "yes" else "no") }
                logger.info("mpv IPC ready pid=${session.process.pid()}")
                reader.buffered().useLines { lines ->
                    lines.forEach { line ->
                        if (isMpvPlaybackStartedEvent(line)) {
                            val callback = synchronized(mpvSessionLock) {
                                session.started = true
                                session.onPlaybackStarted.also { session.onPlaybackStarted = null }
                            }
                            runCatching { callback?.invoke() }
                                .onFailure { logger.log(Level.WARNING, "playback-start callback failed", it) }
                        }
                        mpvEndReason(line)?.let { reason ->
                            val failureMessage = mpvPlaybackFailure(line)
                            logger.info("mpv end-file pid=${session.process.pid()} reason=$reason" +
                                (failureMessage?.let { " failure=$it" } ?: ""))
                            if (reason == "eof") completeMpvItem(session)
                            if (reason == "error" && isMpvVideoOutputFailure(line) && session.windowId != null && !session.started && session.retryExternally?.invoke() == true) return@forEach
                            if (reason == "error") {
                                val callback = synchronized(mpvSessionLock) {
                                    if (mpvSession !== session) return@synchronized null
                                    session.mediaFailed = true
                                    session.onPlaybackStarted = null
                                    session.onPlaybackFailed.also { session.onPlaybackFailed = null }
                                }
                                runCatching { callback?.invoke(failureMessage ?: "mpv could not open this video. Choose another line.") }
                                    .onFailure { logger.log(Level.WARNING, "playback-failure callback failed", it) }
                            }
                        }
                        if (isMpvEofReachedEvent(line)) {
                            logger.info("mpv eof-reached pid=${session.process.pid()}")
                            completeMpvItem(session)
                        }
                        parseMpvPropertyChange(line, session.playbackState)?.let { state ->
                            session.playbackState = state
                            runCatching { session.stateCallback?.onState(state) }
                                .onFailure { logger.log(Level.WARNING, "playback-state callback failed", it) }
                            if (synchronized(mpvSessionLock) { mpvSession === session }) stateListeners.forEach { callback ->
                                runCatching { callback.onState(state) }
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                logger.fine("mpv IPC closed pid=${session.process.pid()} error=${error.javaClass.simpleName}")
            } finally {
                synchronized(mpvSessionLock) {
                    session.writer = null
                    runCatching { channel.close() }
                }
            }
        }, "tvbox-mpv-ipc-${session.process.pid()}").apply { isDaemon = true; start() }
    }

    private fun completeMpvItem(session: MpvSession) {
        val callback = synchronized(mpvSessionLock) {
            if (mpvSession !== session) return
            session.onEnded.also { session.onEnded = null }
        }
        runCatching { callback?.invoke() }.onFailure { error ->
            logger.log(Level.WARNING, "end callback failed player=$name", error)
        }
    }

    private fun closeMpvSession(session: MpvSession) {
        synchronized(mpvSessionLock) {
            if (!session.closed.compareAndSet(false, true)) return
            if (mpvSession === session) mpvSession = null
            session.writer = null
            runCatching { session.channel?.close() }
            runCatching { session.prepared.resource?.close() }
            // Appended items were never handed to `prepared`; their relays are still open.
            session.queued.forEach { runCatching { it.resource?.close() } }
            session.queued.clear()
            runCatching { Files.deleteIfExists(session.socketPath) }
        }
    }

    private fun track(process: Process) {
        val first = synchronized(launchedLock) { launched.isEmpty().also { launched += process } }
        runCatching {
            process.onExit().thenRun { synchronized(launchedLock) { launched -= process } }
        }
        // windowClosing covers the normal path; this also covers System.exit from anywhere else.
        if (first) runCatching {
            Runtime.getRuntime().addShutdownHook(Thread({ runCatching { terminateAll() } }, "tvbox-player-shutdown"))
        }
    }

    private fun stopBeforeReplacement() {
        val previous = synchronized(launchedLock) { launched.toList() }
        terminateAll(invalidateRequest = false)
        previous.forEach { process ->
            if (process.isAlive && !process.waitFor(GRACE_PERIOD_SECONDS, TimeUnit.SECONDS)) {
                synchronized(launchedLock) { launched.addAll(previous.filter { it.isAlive }) }
                throw IllegalStateException("Previous video player is still closing; retry playback.")
            }
        }
    }

    /** True while a player this adapter started is still running. */
    fun isRunning(): Boolean = synchronized(mpvSessionLock) { mpvSession != null } ||
        synchronized(launchedLock) { launched.any { it.isAlive } }

    /** Stops the running player. mpv is asked to quit over IPC so it can clean up its socket. */
    fun stop(): Boolean {
        stopGeneration.incrementAndGet()
        playbackRequest.incrementAndGet()
        embeddedHost?.playbackClosed()
        val session = synchronized(mpvSessionLock) { mpvSession }
        if (session != null) {
            quitMpv(session)
            return true
        }
        return terminateAll()
    }

    /** mpv playlist transport. Other players are started per item and have nothing to move. */
    fun next(): Boolean = mpvCommand("playlist-next")

    fun previous(): Boolean = mpvCommand("playlist-prev")

    /**
     * Adds an item to the running mpv playlist instead of replacing it, so mpv owns the queue and
     * [next]/[previous] have something to walk. Falls back to starting playback when nothing runs.
     */
    fun enqueue(
        value: String,
        headers: Map<String, String> = emptyMap(),
        displayName: String? = null,
    ): PlayerLaunchResult {
        if (com.wuming.tv.core.source.PlaybackProtocolPolicy.unsupported(value)) {
            return PlayerLaunchResult(false, "This playback protocol is not supported in this build. Choose another source.")
        }
        val resolved = resolve(value, headers)
            ?: return PlayerLaunchResult(false, "Enter an HTTP(S), file URL, or existing local file.")
        val session = synchronized(mpvSessionLock) { mpvSession }
            ?: return play(value, headers, displayName = displayName)
        if (!name.equals("mpv", true)) {
            return PlayerLaunchResult(false, "$name plays one item at a time.")
        }
        return try {
            val prepared = targetPreparer.prepare(resolved.target, resolved.safeHeaders)
            if (enqueueInMpvSession(prepared, resolved.safeHeaders, null, null, displayName, mode = "append")) {
                logger.info("queued player=$name target=${tag(resolved.target)}")
                PlayerLaunchResult(true, "Queued in $name")
            } else {
                prepared.resource?.close()
                PlayerLaunchResult(false, "$name is not responding.")
            }
        } catch (error: PlaybackPreparationException) {
            logger.warning("queue unavailable player=$name error=${error.message}")
            PlayerLaunchResult(false, error.message ?: "Playback source is unavailable.")
        } catch (error: Exception) {
            logger.log(Level.WARNING, "queue failed player=$name error=${error.message}", error)
            PlayerLaunchResult(false, "$name could not queue: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    /**
     * Never leave a player behind when the app exits. mpv is quit politely first; anything still
     * alive after the grace period is killed.
     */
    fun terminateAll(): Boolean = terminateAll(invalidateRequest = true)

    private fun terminateAll(invalidateRequest: Boolean): Boolean {
        if (invalidateRequest) {
            stopGeneration.incrementAndGet()
            playbackRequest.incrementAndGet()
            embeddedHost?.playbackClosed()
        }
        val session = synchronized(mpvSessionLock) { mpvSession }
        if (session != null) quitMpv(session)
        val stragglers = synchronized(launchedLock) { launched.toList() }
        var stopped = false
        stragglers.forEach { process ->
            if (!process.isAlive) return@forEach
            stopped = true
            process.destroy()
            if (!process.waitFor(GRACE_PERIOD_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
            logger.info("terminated player=$name pid=${process.pid()}")
        }
        synchronized(launchedLock) { launched.clear() }
        return stopped || session != null
    }

    private fun quitMpv(session: MpvSession) {
        synchronized(mpvSessionLock) { session.onEnded = null; session.onPlaybackStarted = null; session.onPlaybackFailed = null }
        runCatching {
            synchronized(mpvSessionLock) {
                session.writer?.apply {
                    write("""{"command":["quit"]}""")
                    newLine()
                    flush()
                }
            }
        }
        if (session.process.isAlive && !session.process.waitFor(GRACE_PERIOD_SECONDS, TimeUnit.SECONDS)) {
            session.process.destroyForcibly()
        }
        closeMpvSession(session)
    }

    private fun mpvCommand(vararg command: String): Boolean = synchronized(mpvSessionLock) {
        val session = mpvSession ?: return false
        val writer = session.writer ?: return false
        return runCatching {
            val arguments = command.joinToString(",") { jsonString(it) }
            writer.write("""{"command":[$arguments]}""")
            writer.newLine()
            writer.flush()
            true
        }.getOrDefault(false)
    }

    private fun mpvSetProperty(property: String, value: String): Boolean = synchronized(mpvSessionLock) {
        val session = mpvSession ?: return false
        val writer = session.writer ?: return false
        return runCatching {
            writer.write("""{"command":["set_property",${jsonString(property)},${jsonString(value)}]}""")
            writer.newLine()
            writer.flush()
            true
        }.getOrDefault(false)
    }

    private fun resolve(value: String, headers: Map<String, String>): Resolved? {
        val target = normalizeTarget(value) ?: return null
        val safeHeaders = headers.filter { (name, content) ->
            name.isNotBlank() && name.none(Character::isISOControl) &&
                content.none(Character::isISOControl)
        }.toMutableMap()
        if (target.startsWith("http://", true) || target.startsWith("https://", true)) {
            if (safeHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                // Android's PlaySpec.checkUa() always supplies a playback UA. Some HLS CDNs
                // reject VLC's native User-Agent even though both the manifest and segments are
                // otherwise public, so keep desktop transport behavior compatible with Leanback.
                safeHeaders["User-Agent"] = DEFAULT_PLAYBACK_USER_AGENT
            }
        }
        return Resolved(target, safeHeaders)
    }

    private data class Resolved(val target: String, val safeHeaders: MutableMap<String, String>)

    private fun transportHeaders(headers: Map<String, String>): List<Pair<String, String>> = headers.entries
        .map { it.key.lowercase() to it.value }
        .sortedBy(Pair<String, String>::first)

    private val GRACE_PERIOD_SECONDS = 2L

    private fun newMpvSocketPath(): Path = Path.of(
        System.getProperty("java.io.tmpdir"),
        "tvbox-mpv-${UUID.randomUUID().toString().replace("-", "")}.sock",
    )

    private fun jsonString(value: String): String = com.google.gson.Gson().toJson(value)

    private fun monitorOutput(process: Process, session: MpvSession? = null): CompletableFuture<Boolean> {
        val completion = CompletableFuture<Boolean>()
        Thread({
            val reported = mutableSetOf<String>()
            var naturalEnd = false
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (isNaturalEndDiagnostic(line)) naturalEnd = true
                        classifyPlayerOutput(line)?.takeIf(reported::add)?.let { event ->
                            if (event.startsWith("http-status-") || event == "input-format-failed" || event == "input-open-failed") {
                                session?.mediaFailed = true
                            }
                            logger.info("diagnostic player=$name pid=${process.pid()} event=$event")
                        }
                    }
                }
            } catch (error: Exception) {
                logger.fine("player diagnostic stream closed player=$name pid=${process.pid()} error=${error.javaClass.simpleName}")
            } finally {
                completion.complete(naturalEnd)
            }
        }, "tvbox-player-output-${process.pid()}").apply {
            isDaemon = true
            start()
        }
        return completion
    }

    private fun tag(value: String): String = "len=${value.length},sha=${Digests.sha256Hex(value).take(12)}"

    internal fun normalizeTarget(value: String): String? {
        val input = value.trim()
        if (input.isEmpty() || input.any(Character::isISOControl)) return null
        try {
            val uri = URI(input)
            if (uri.scheme != null) {
                return when (uri.scheme.lowercase()) {
                    "http", "https" -> if (!uri.host.isNullOrBlank()) uri.toASCIIString() else null
                    "file" -> uri.toString()
                    else -> null
                }
            }
        } catch (_: Exception) {
            // Filesystem paths may contain spaces or characters that URI syntax rejects.
        }
        return try {
            val path = Path.of(input).toAbsolutePath().normalize()
            if (Files.isRegularFile(path)) path.toUri().toString() else null
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        internal const val DEFAULT_PLAYBACK_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 9; Android TV) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    private data class MpvSession(
        val process: Process,
        val socketPath: Path,
        val transportHeaders: List<Pair<String, String>>,
        var prepared: PreparedPlaybackTarget,
        var onEnded: (() -> Unit)?,
        var onClosed: (() -> Unit)?,
        var channel: SocketChannel? = null,
        var writer: java.io.BufferedWriter? = null,
        val queued: MutableList<PreparedPlaybackTarget> = mutableListOf(),
        @Volatile var stateCallback: PlaybackStateCallback? = null,
        @Volatile var playbackState: PlaybackState = PlaybackState(),
        var onPlaybackStarted: (() -> Unit)? = null,
        var onPlaybackFailed: ((String) -> Unit)? = null,
        val windowId: Long? = null,
        @Volatile var started: Boolean = false,
        @Volatile var mediaFailed: Boolean = false,
        var retryExternally: (() -> Boolean)? = null,
        val retryStarted: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(),
        val closed: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(),
        val subtitles: MutableSet<String> = linkedSetOf(),
    )
}

internal fun isMpvPlaybackStartedEvent(line: String): Boolean = runCatching {
    com.google.gson.JsonParser.parseString(line).asJsonObject.get("event")?.asString == "playback-restart"
}.getOrDefault(false)

internal fun mpvEndReason(line: String): String? = runCatching {
    val value = com.google.gson.JsonParser.parseString(line).asJsonObject
    value.takeIf { it.get("event")?.asString == "end-file" }?.get("reason")?.asString
}.getOrNull()

/** mpv's error strings are mapped to fixed text so provider URLs cannot leak into logs or UI. */
internal fun mpvPlaybackFailure(line: String): String? {
    if (mpvEndReason(line) != "error") return null
    val error = runCatching { com.google.gson.JsonParser.parseString(line).asJsonObject.get("file_error")?.asString }
        .getOrNull().orEmpty().lowercase()
    return when {
        "unrecognized file format" in error -> "mpv could not recognize the media format. The provider may have returned an invalid playlist. Choose another line."
        "loading failed" in error || "failed to open" in error -> "mpv could not load the media from the provider. Retry or choose another line."
        isMpvVideoOutputFailure(line) -> "mpv could not initialize video output."
        else -> "mpv could not open this video. Retry or choose another line."
    }
}

internal fun isMpvVideoOutputFailure(line: String): Boolean = runCatching {
    val error = com.google.gson.JsonParser.parseString(line).asJsonObject.get("file_error")?.asString.orEmpty()
    error.contains("video output", true) || error.contains("audio/video output", true) ||
        // mpv 0.38 reports this when a selected renderer cannot initialize.
        error.equals("no audio or video data played", true)
}.getOrDefault(false)

internal fun isMpvNaturalEndEvent(line: String): Boolean = mpvEndReason(line) == "eof"

internal fun isMpvEofReachedEvent(line: String): Boolean = runCatching {
    val value = com.google.gson.JsonParser.parseString(line).asJsonObject
    value.get("event")?.asString == "property-change" &&
        value.get("name")?.asString == "eof-reached" &&
        value.get("data")?.asBoolean == true
}.getOrDefault(false)

internal fun parseMpvPropertyChange(line: String, previous: PlaybackState = PlaybackState()): PlaybackState? = runCatching {
    val value = com.google.gson.JsonParser.parseString(line).asJsonObject
    if (value.get("event")?.asString != "property-change") return@runCatching null
    val name = value.get("name")?.asString ?: return@runCatching null
    val data = value.get("data") ?: return@runCatching null
    when (name) {
        "time-pos" -> previous.copy(positionSeconds = data.asDouble.takeIf { it.isFinite() && it >= 0 } ?: previous.positionSeconds)
        "duration" -> previous.copy(durationSeconds = data.asDouble.takeIf { it.isFinite() && it >= 0 } ?: previous.durationSeconds)
        "pause" -> previous.copy(paused = data.asBoolean)
        "speed" -> previous.copy(speed = data.asDouble.takeIf { it.isFinite() && it > 0 } ?: previous.speed)
        "paused-for-cache" -> previous.copy(buffering = data.asBoolean)
        else -> null
    }
}.getOrNull()

internal fun classifyPlayerOutput(line: String): String? = when {
    isNaturalEndDiagnostic(line) -> "end-of-media"
    line.contains("Received first picture", ignoreCase = true) -> "first-video-frame"
    line.contains("Stream buffering done", ignoreCase = true) -> "buffering-complete"
    line.contains("using demux module \"adaptive\"", ignoreCase = true) -> "hls-demux-ready"
    line.contains("using demux module \"ts\"", ignoreCase = true) -> "transport-demux-ready"
    line.contains("Failed to recognize file format", ignoreCase = true) -> "input-format-failed"
    line.contains("cannot be opened", ignoreCase = true) -> "input-open-failed"
    line.contains("input error", ignoreCase = true) -> "input-error"
    line.contains("connection failed", ignoreCase = true) &&
        (line.contains("access error", ignoreCase = true) || line.contains("http", ignoreCase = true)) -> "connection-failed"
    else -> HTTP_STATUS.find(line)?.groupValues?.get(1)
        ?.takeIf { it.toInt() >= 400 }
        ?.let { "http-status-$it" }
}

internal fun isNaturalEndDiagnostic(line: String): Boolean =
    line.contains("end of playlist, exiting", ignoreCase = true) ||
        line.contains("Exiting... (End of file)", ignoreCase = true)

private val HTTP_STATUS = Regex("(?:answer code|status|HTTP error)[^0-9]{0,8}([1-5][0-9]{2})", RegexOption.IGNORE_CASE)

object ExternalPlayerDiscovery {

    private val playerNames = listOf("mpv", "vlc")
    private val knownLocations = listOf(
        Path.of("/usr/bin/mpv"),
        Path.of("/usr/local/bin/mpv"),
        Path.of("/usr/bin/vlc"),
        Path.of("/usr/local/bin/vlc"),
    )

    fun discover(pathValue: String? = System.getenv("PATH")): ExternalPlayerAdapter? {
        val pathCandidates = pathValue.orEmpty().split(System.getProperty("path.separator"))
            .filter(String::isNotBlank)
            .flatMap { directory -> playerNames.map { Path.of(directory, it) } }
        return (pathCandidates + knownLocations)
            .distinct()
            .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
            ?.let(::ExternalPlayerAdapter)
    }
}
