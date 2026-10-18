package com.wuming.android.tv.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.ui.danmaku.DanmakuConfig
import com.wuming.android.tv.App
import com.wuming.android.tv.Constant
import com.wuming.android.tv.R
import com.wuming.android.tv.bean.Danmaku
import com.wuming.android.tv.bean.BeanUtils
import com.wuming.android.tv.bean.Result
import com.wuming.android.tv.bean.Sub
import com.wuming.android.tv.bean.Track
import com.wuming.android.tv.impl.ParseCallback
import com.wuming.android.tv.player.danmaku.DanPlayer
import com.wuming.android.tv.player.engine.ExoPlayerEngine
import com.wuming.android.tv.player.engine.PlaySpec
import com.wuming.android.tv.player.engine.PlayerEngine
import com.wuming.android.tv.setting.PlayerSettings
import com.wuming.android.tv.utils.Notify
import com.wuming.android.tv.utils.QuarkPlaybackTrace
import com.wuming.android.tv.utils.ResUtil
import com.wuming.android.tv.utils.SitePerformanceTracker
import com.wuming.android.tv.utils.Task
import com.wuming.android.tv.utils.UiTaskScope
import com.wuming.android.tv.utils.Util
import com.wuming.android.tv.utils.VideoNetworkPolicy
import com.wuming.android.tv.cloud.opendrive.OpenDriveCopyUsage
import com.google.common.net.HttpHeaders
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.HashMap
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executor
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.function.Function
import kotlin.math.max
import kotlin.math.min
import master.flame.danmaku.ui.widget.DanmakuView

open class PlayerManager constructor(
    private val callback: Callback?,
    injectedEngine: PlayerEngine?,
    private val trackExecutor: Executor,
    private val trackLoader: Function<String, List<Track>>,
) : ParseCallback {

    private var runnable: Runnable
    private val callbackRef = WeakReference<Callback>(callback)
    @Volatile private var engine: PlayerEngine?
    @Volatile private var danPlayer: DanPlayer? = null
    private var videoSize: VideoSize? = null
    private var parseJob: ParseJob? = null
    @Volatile private var spec: PlaySpec? = null
    @Volatile private var player: Player?
    private val listener: Player.Listener
    private var decodeRetry: Runnable? = null
    private var trackTask: Future<*>? = null
    @Volatile private var generation = 0L
    @Volatile private var initTrack = false
    @Volatile private var retry = 0
    private var cloudCopyUsage: AutoCloseable? = null
    private val tasks = UiTaskScope()
    private val relayAssistance = com.wuming.android.tv.player.relay.DesktopRelayAssistance(
        object : com.wuming.android.tv.player.relay.DesktopRelayAssistance.Host {
            override fun spec(): PlaySpec? = spec
            override fun player(): Player? = player
            override fun restart() { setMediaItem() }
        }
    )

    init {
        listener = PlayerListener(this)
        runnable = TimeoutErrorRunnable(this)
        engine = injectedEngine ?: ExoPlayerEngine(PlayerEngine.HARD, listener)
        player = engine!!.getPlayer()
        if (injectedEngine != null) {
            player?.addListener(listener)
        }
    }

    constructor(callback: Callback?) : this(callback, null, Task::execute, Function { Track.find(it) })

    constructor(callback: Callback?, injectedEngine: PlayerEngine?) :
        this(callback, injectedEngine, Task::execute, Function { Track.find(it) })

    // ----- Projection of player state (Java-style getters so both Java and Kotlin callers work) -----

    fun getPlayer(): Player? = player

    fun getCurrentMediaItem(): MediaItem? = player?.currentMediaItem

    fun getPlaybackState(): Int = player?.playbackState ?: Player.STATE_IDLE

    fun isPlaying(): Boolean = player != null && player!!.isPlaying

    fun getUrl(): String? = spec?.getUrl()

    /** Changes when playback is replaced, stopped, rebuilt or released. Read on the player thread. */
    fun getPlaybackGeneration(): Long = generation

    fun getKey(): String? = spec?.getKey()

    fun getDanmakus(): List<Danmaku>? = spec?.getDanmakus()

    fun getHeaders(): Map<String, String> =
        if (spec == null) Collections.emptyMap() else BeanUtils.emptyMapIfNull(spec!!.getHeaders())

    fun getSpeed(): Float {
        if (player == null) return 1.0f
        return try {
            player!!.playbackParameters.speed
        } catch (e: Exception) {
            1.0f
        }
    }

    fun isEmpty(): Boolean = spec == null || BeanUtils.isEmpty(spec!!.getUrl())

    fun isLive(): Boolean = engine?.isLive() ?: false

    fun isVod(): Boolean = engine?.isVod() ?: false

    fun getCurrentTracks(): Tracks = engine?.getCurrentTracks() ?: Tracks.EMPTY

    fun getSelectedAudioTrackFormat(): String? {
        val p = player ?: return null
        for (group in p.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                if (group.isTrackSelected(i)) {
                    return PlayerHelper.describeFormat(group.getTrackFormat(i))
                }
            }
        }
        return null
    }

    fun getVideoWidth(): Int = videoSize?.width ?: 0

    fun getVideoHeight(): Int = videoSize?.height ?: 0

    fun getPosition(): Long = player?.currentPosition ?: 0

    fun getSizeText(): String = if (getVideoWidth() == 0 && getVideoHeight() == 0) "" else getVideoWidth().toString() + " x " + getVideoHeight().toString()

    fun getCodecInfo(): String {
        val p = player ?: return ""
        try {
            for (group in p.currentTracks.groups) {
                if (group.type == C.TRACK_TYPE_VIDEO && group.length > 0) {
                    val format = group.getTrackFormat(0)
                    val sb = StringBuilder()
                    val mime = format.sampleMimeType
                    if (mime != null) {
                        when {
                            mime.contains("av1") -> sb.append("AV1")
                            mime.contains("hevc") || mime.contains("h265") -> sb.append("H265")
                            mime.contains("avc") || mime.contains("h264") -> sb.append("H264")
                            mime.contains("vp9") -> sb.append("VP9")
                            mime.contains("vp8") -> sb.append("VP8")
                            else -> sb.append(mime.replace("video/", "").uppercase(Locale.ROOT))
                        }
                    }
                    return sb.toString()
                }
            }
        } catch (ignored: Exception) {
        }
        return ""
    }

    fun getSpeedText(): String = String.format(Locale.getDefault(), "%.2f", getSpeed())

    fun getDecodeText(): String = engine?.getDecodeText() ?: ""

    fun getDuration(): Long = player?.duration ?: 0

    fun getDurationTime(): String = Util.timeMs(max(0L, getDuration()))

    open fun getMetadata(): MediaMetadata? = spec?.getMetadata()

    // These two delegate to the danmaku view; kept as properties for symmetric get/set access.
    var audioOffsetMs: Long
        get() = danPlayer?.audioOffsetMs ?: 0
        set(value) {
            danPlayer?.audioOffsetMs = value
        }

    var textOffsetMs: Long
        get() = danPlayer?.textOffsetMs ?: 0
        set(value) {
            danPlayer?.textOffsetMs = value
        }

    // ----- Derived helpers -----

    fun isPortrait(): Boolean = getVideoHeight() > getVideoWidth()

    fun isLandscape(): Boolean = getVideoWidth() > getVideoHeight()

    fun haveTrack(type: Int): Boolean = engine?.haveTrack(type) ?: false

    fun haveDanmaku(): Boolean {
        val items = getDanmakus() ?: return false
        for (d in items) {
            if (d.isSelected()) return true
        }
        return false
    }

    fun canSetOpening(position: Long, duration: Long): Boolean =
        position > 0 && duration > 0 && position <= Constant.getOpEdLimit(duration)

    fun canSetEnding(position: Long, duration: Long): Boolean =
        position > 0 && position <= duration && duration - position <= Constant.getOpEdLimit(duration)

    fun getPositionTime(delta: Long): String {
        val time = max(0L, min(getPosition() + delta, max(0L, getDuration())))
        return Util.timeMs(time)
    }

    // ----- Mutators -----

    private fun releaseCloudCopy() {
        if (cloudCopyUsage != null) {
            try {
                cloudCopyUsage!!.close()
            } catch (ignored: Exception) {
            }
            cloudCopyUsage = null
        }
    }

    fun release() {
        relayAssistance.close()
        cancelPendingPlaybackWork()
        tasks.close()
        callbackRef.clear()
        stopParse()
        danPlayer?.release()
        danPlayer = null
        if (engine != null) {
            player?.removeListener(listener)
            engine!!.release()
            engine = null
            player = null
        }
        releaseCloudCopy()
        spec = null
        videoSize = null
    }

    fun setSub(sub: Sub?) {
        spec?.setSub(sub)
        setMediaItem()
    }

    fun setFormat(format: String?) {
        spec?.setFormat(format)
        setMediaItem()
    }

    companion object {
        @JvmStatic
        fun buildMetadata(title: String?, artist: String?, artUri: String?): MediaMetadata {
            val artwork = if (BeanUtils.isEmpty(artUri)) null else Uri.parse(artUri)
            return MediaMetadata.Builder().setTitle(title).setArtist(artist).setArtworkUri(artwork).build()
        }
    }

    fun setMetadata(data: MediaMetadata) {
        spec?.setMetadata(data)
        engine?.setMetadata(data)
    }

    fun setDanmakuView(view: DanmakuView?) {
        danPlayer?.release()
        danPlayer = if (player == null || view == null) null else DanPlayer(view)
        if (player != null) danPlayer?.attachPlayer(player)
    }

    fun setSubtitleAvoidance(subtitleVisible: Boolean) {
        danPlayer?.setSubtitleAvoidance(subtitleVisible)
    }

    fun clearDanmakuView(view: DanmakuView?) {
        if (danPlayer == null || !danPlayer!!.ownsView(view)) return
        danPlayer!!.release()
        danPlayer = null
    }

    fun setDanmakuSize(size: Float) {
        danPlayer?.setTextSize(size)
    }

    fun setSpeed(speed: Float): String {
        val p = player ?: return getSpeedText()
        if (!p.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) return getSpeedText()
        p.setPlaybackParameters(p.playbackParameters.withSpeed(speed))
        return getSpeedText()
    }

    fun addSpeed(): String {
        val s = getSpeed()
        val addon = if (s >= 2) 1f else 0.25f
        val next = if (s >= 5) 0.25f else min(s + addon, 5.0f)
        return setSpeed(next)
    }

    fun addSpeed(value: Float): String = setSpeed(min(getSpeed() + value, 5f))

    fun subSpeed(value: Float): String = setSpeed(max(getSpeed() - value, 0.25f))

    fun toggleSpeed(): String = setSpeed(if (getSpeed() == 1f) PlayerSettings.getSpeed() else 1f)

    fun setTrack(tracks: List<Track>) {
        if (engine != null && tracks.isNotEmpty()) engine!!.setTrack(tracks)
    }

    fun play() {
        player?.play()
        relayAssistance.stateChanged()
    }

    fun pause() {
        player?.pause()
        relayAssistance.stateChanged()
    }

    fun stop() {
        relayAssistance.stop()
        cancelPendingPlaybackWork()
        danPlayer?.stop()
        player?.stop()
        stopParse()
        releaseCloudCopy()
    }

    fun setRepeatOne(repeat: Boolean) {
        player?.setRepeatMode(if (repeat) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF)
    }

    fun seekTo(time: Long) {
        player?.seekTo(time)
    }

    fun reset() {
        App.removeCallbacks(runnable)
        retry = 0
    }

    fun clear() {
        relayAssistance.stop()
        // Clearing metadata alone does not stop the media engine; retain its copy lease.
        cancelPendingPlaybackWork()
        stopParse()
        spec = null
    }

    fun resetTrack() {
        engine?.resetTrack()
    }

    fun toggleDecode() {
        // Delayed posts may run after release() nulled the engine on another thread.
        val engine = this.engine ?: return
        engine.setDecode(if (engine.isHard()) PlayerEngine.SOFT else PlayerEngine.HARD)
        rebuildPlayer()
        setMediaItem()
    }

    private fun rebuildPlayer() {
        val engine = this.engine ?: return
        cancelPendingPlaybackWork()
        danPlayer?.detachPlayer()
        val p = engine.rebuild(listener)
        player = p
        danPlayer?.attachPlayer(p)
        callbackRef.get()?.onPlayerRebuild(p)
    }

    fun start(spec: PlaySpec, timeout: Long) {
        if (engine == null) return
        stopParse()
        retry = 0
        relayAssistance.reset()
        this.spec = spec
        setMediaItem(timeout)
    }

    fun parse(key: String, result: Result, useParse: Boolean, metadata: MediaMetadata) {
        if (engine == null) return
        cancelPendingPlaybackWork()
        retry = 0
        stopParse()
        relayAssistance.stop()
        relayAssistance.reset()
        spec = PlaySpec.fromParse(result, key, metadata)
        parseJob = ParseJob.create(this).start(result, useParse)
    }

    private fun stopParse() {
        parseJob?.stop()
        parseJob = null
    }

    private fun cancelPendingPlaybackWork() {
        generation++
        initTrack = false
        App.removeCallbacks(runnable)
        decodeRetry?.let { tasks.remove(it) }
        decodeRetry = null
        trackTask?.cancel(true)
        trackTask = null
    }

    fun setMediaItem() {
        setMediaItem(Constant.TIMEOUT_PLAY)
    }

    private fun setMediaItem(timeout: Long) {
        cancelPendingPlaybackWork()
        // Capture the reference once: clear() may run on another thread and null the
        // field between the guard and the dereferences below.
        val spec = this.spec
        val engine = this.engine
        if (spec == null || BeanUtils.isEmpty(spec.getUrl()) || engine == null) return
        relayAssistance.prepare(spec)
        val blockedMessage = if (VideoNetworkPolicy.isNetworkVideo(spec.getUrl())) VideoNetworkPolicy.blockedMessage() else 0
        if (blockedMessage != 0) {
            callbackRef.get()?.onError(ResUtil.getString(blockedMessage))
            return
        }
        val nextCopy = OpenDriveCopyUsage.pin(spec.getHeaders())
        releaseCloudCopy()
        cloudCopyUsage = nextCopy
        setDanmakus(spec.getDanmakus())
        // Arm the timeout before start(). Media3 can synchronously enter BUFFERING while
        // start() is still on the stack; posting afterwards loses that state callback and
        // reports a false timeout even after tracks and the first frame have arrived.
        App.post(runnable, timeout)
        val request = generation
        engine.start(spec.checkUa())
        relayAssistance.stateChanged()
        if (request != generation) return
        callbackRef.get()?.onPrepare()
    }

    fun startBrowse(spec: PlaySpec) {
        start(spec, Constant.TIMEOUT_PLAY)
    }

    private fun setDanmakus(items: List<Danmaku>?) {
        if (danPlayer != null) setDanmaku(if (items == null || items.isEmpty()) Danmaku.empty() else items[0])
    }

    fun setDanmaku(item: Danmaku?) {
        spec?.setDanmaku(item)
        danPlayer?.setDanmaku(item)
    }

    fun addDanmaku(item: Danmaku?) {
        danPlayer?.addDanmaku(item)
    }

    fun setDanmakuConfig(config: DanmakuConfig?) {
        danPlayer?.setConfig(config)
    }

    fun setDanmakuEnabled(enabled: Boolean) {
        danPlayer?.setEnabled(enabled)
    }

    override fun onParseSuccess(headers: Map<String, String>?, url: String?, from: String?) {
        if (BeanUtils.isNotEmpty(from)) Notify.show(ResUtil.getString(R.string.parse_from, from))
        val newHeaders = if (headers != null) {
            HashMap(headers).also { it.keys.removeIf { k -> HttpHeaders.RANGE.equals(k, ignoreCase = true) } }
        } else {
            null
        }
        if (spec != null) {
            spec!!.setHeaders(newHeaders)
            spec!!.setUrl(url)
        }
        setMediaItem()
    }

    override fun onParseError() {
        callbackRef.get()?.onError(ResUtil.getString(R.string.error_play_parse))
    }

    interface Callback {
        fun onPrepare()

        fun onTracksChanged()

        fun onTitlesChanged()

        fun onError(msg: String)

        fun onPlayerRebuild(newPlayer: Player)

        fun onRefreshUrl() {}
    }

    private class PlayerListener(manager: PlayerManager) : Player.Listener {
        private val managerRef = WeakReference(manager)

        override fun onPlaybackStateChanged(state: Int) {
            QuarkPlaybackTrace.write("player state=$state")
            val manager = managerRef.get() ?: return
            if (state != Player.STATE_IDLE) App.removeCallbacks(manager.runnable)
            if (state == Player.STATE_READY) manager.retry = 0
            manager.relayAssistance.stateChanged()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            managerRef.get()?.relayAssistance?.stateChanged()
        }

        override fun onRenderedFirstFrame() {
            QuarkPlaybackTrace.write("video firstFrame=true")
        }

        override fun onVideoSizeChanged(size: VideoSize) {
            QuarkPlaybackTrace.write("video size=" + size.width + "x" + size.height)
            val manager = managerRef.get() ?: return
            manager.videoSize = size
        }

        override fun onTracksChanged(tracks: Tracks) {
            val manager = managerRef.get() ?: return
            if (manager.engine == null || tracks.isEmpty) return
            var hasSubtitle = false
            for (group in tracks.groups) {
                if (group.type == C.TRACK_TYPE_TEXT) {
                    for (i in 0 until group.length) {
                        if (group.isTrackSelected(i)) { hasSubtitle = true; break }
                    }
                }
                if (hasSubtitle) break
            }
            manager.setSubtitleAvoidance(hasSubtitle)
            if (manager.initTrack) return
            val key = manager.getKey()
            val generation = manager.generation
            val scope = manager.tasks
            val ref = managerRef
            val loader = manager.trackLoader
            manager.initTrack = true
            val lookup = FutureTask<Void>(Callable {
                val trackList = loader.apply(key ?: "")
                if (Thread.currentThread().isInterrupted) return@Callable null
                scope.post({
                    val current = ref.get()
                    if (current != null && current.engine != null && current.generation == generation && key == current.getKey()) {
                        current.setTrack(trackList)
                    }
                }, 0L)
                null
            })
            manager.trackTask = lookup
            manager.trackExecutor.execute(lookup)
            manager.callbackRef.get()?.onTracksChanged()
        }

        override fun onPlayerError(e: PlaybackException) {
            QuarkPlaybackTrace.write("player error=" + e.errorCode)
            val manager = managerRef.get() ?: return
            val engine = manager.engine ?: return
            App.removeCallbacks(manager.runnable)
            manager.decodeRetry?.let { manager.tasks.remove(it) }
            manager.decodeRetry = null
            val action = engine.handleError(e)
            if (action == PlayerEngine.ErrorAction.RECOVERED) return
            // A live-window seek or format recovery must retain the fast relay route.
            // Only terminal playback failures warrant replacing it with the original URL.
            if (action == PlayerEngine.ErrorAction.FATAL &&
                com.wuming.android.tv.player.relay.DesktopRelayClient.fallback(manager.spec)) {
                manager.setMediaItem(); return
            }
            if (++manager.retry > 3) {
                manager.callbackRef.get()?.onError(engine.getErrorMessage(e))
                return
            }
            when (action) {
                PlayerEngine.ErrorAction.DECODE -> {
                    val generation = manager.generation
                    val ref = managerRef
                    manager.decodeRetry = Runnable {
                        val current = ref.get()
                        if (current == null || current.engine == null || current.generation != generation) return@Runnable
                        current.decodeRetry = null
                        current.toggleDecode()
                    }
                    manager.tasks.post(manager.decodeRetry!!, 1000L * manager.retry)
                }
                PlayerEngine.ErrorAction.REFRESH -> {
                    manager.callbackRef.get()?.onRefreshUrl()
                }
                PlayerEngine.ErrorAction.RETRY -> {
                    val generation = manager.generation
                    val ref = managerRef
                    manager.decodeRetry = Runnable {
                        val current = ref.get()
                        if (current == null || current.engine == null || current.generation != generation) return@Runnable
                        current.decodeRetry = null
                        current.engine?.retryAfterError()
                    }
                    manager.tasks.post(manager.decodeRetry!!, engine.pendingRetryDelayMs())
                }
                PlayerEngine.ErrorAction.FATAL -> {
                    manager.callbackRef.get()?.onError(engine.getErrorMessage(e))
                }
            }
        }
    }

    private class TimeoutErrorRunnable(manager: PlayerManager) : Runnable {
        private val managerRef = WeakReference(manager)

        override fun run() {
            val manager = managerRef.get() ?: return
            // A bounded background trial may cross the ordinary direct startup deadline.
            if (manager.relayAssistance.isPreparing()) {
                App.post(manager.runnable, 1000L)
                return
            }
            if (com.wuming.android.tv.player.relay.DesktopRelayClient.fallback(manager.spec)) {
                manager.setMediaItem(); return
            }
            manager.callbackRef.get()?.onError(ResUtil.getString(R.string.error_play_timeout))
        }
    }

    /**
     * Record a dead link (no valid URL)
     */
    fun recordDeadLink(siteKey: String?) {
        if (siteKey.isNullOrEmpty()) return
        SitePerformanceTracker.get().recordDeadLink(siteKey)
    }
}
