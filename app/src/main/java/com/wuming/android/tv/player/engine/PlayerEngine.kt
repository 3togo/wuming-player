package com.wuming.android.tv.player.engine

import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import com.wuming.android.tv.bean.Track

interface PlayerEngine {
    fun getPlayer(): Player

    fun release()

    fun rebuild(listener: Player.Listener): Player

    fun getDecode(): Int

    fun setDecode(decode: Int)

    fun isHard(): Boolean

    fun getDecodeText(): String

    fun start(spec: PlaySpec)

    fun setMetadata(data: MediaMetadata)

    fun isLive(): Boolean

    fun isVod(): Boolean

    fun setTrack(tracks: List<@JvmSuppressWildcards Track>)

    fun resetTrack()

    fun haveTrack(type: Int): Boolean

    fun getCurrentTracks(): Tracks

    fun getErrorMessage(e: PlaybackException): String

    fun handleError(e: PlaybackException): ErrorAction

    /** Delay and execute an engine-owned restart after a recoverable load error. */
    fun pendingRetryDelayMs(): Long

    fun retryAfterError()

    enum class ErrorAction {
        RECOVERED,
        DECODE,
        RETRY,
        REFRESH,
        FATAL
    }

    companion object {
        const val SOFT = 0
        const val HARD = 1
    }
}
