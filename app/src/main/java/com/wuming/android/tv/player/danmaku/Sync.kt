package com.wuming.android.tv.player.danmaku

import android.os.SystemClock
import androidx.media3.common.Player
import com.wuming.android.tv.utils.UiTaskScope
import master.flame.danmaku.danmaku.model.AbsDanmakuSync

/** Samples Media3 on its application thread; the render thread reads a snapshot. */
class Sync(
    private val player: Player,
) : AbsDanmakuSync(),
    Player.Listener,
    AutoCloseable {
    private data class Snapshot(
        val positionMs: Long,
        val sampledAtMs: Long,
        val speed: Float,
        val playing: Boolean,
    )

    private val tasks = UiTaskScope()
    private val sample = Runnable { sample() }

    @Volatile private var snapshot = Snapshot(0, 0, 1f, false)

    init {
        player.addListener(this)
        sample()
    }

    private fun sample() {
        tasks.remove(sample)
        val playing = player.isPlaying
        snapshot = Snapshot(player.currentPosition, SystemClock.elapsedRealtime(), player.playbackParameters.speed, playing)
        if (playing) tasks.post(sample, 500)
    }

    override fun onEvents(
        player: Player,
        events: Player.Events,
    ) {
        sample()
    }

    override fun close() {
        tasks.close()
        player.removeListener(this)
    }

    override fun getUptimeMillis(): Long {
        val current = snapshot
        val elapsed = if (current.playing) maxOf(0, SystemClock.elapsedRealtime() - current.sampledAtMs) else 0
        return current.positionMs + (elapsed * current.speed).toLong()
    }

    override fun getSyncState(): Int = if (snapshot.playing) SYNC_STATE_PLAYING else SYNC_STATE_HALT

    override fun getThresholdTimeMills(): Long = 1000

    override fun isSyncPlayingState(): Boolean = true
}
