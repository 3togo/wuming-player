package com.wuming.android.tv.ui.base

import androidx.annotation.StringRes
import com.wuming.android.tv.R
import com.wuming.android.tv.player.PlayerManager
import com.wuming.android.tv.setting.PlayerSettings
import com.wuming.android.tv.utils.ResUtil
import com.wuming.android.tv.utils.VideoNetworkPolicy

/** Shared presentation state; each flavor decides where and how to render it. */
@JvmRecord data class PlaybackOverlayState(
    val videoInfo: String,
    val showVideoInfo: Boolean,
    val showClock: Boolean,
    @StringRes val networkWarning: Int,
) {
    companion object {
        @JvmStatic fun create(
            player: PlayerManager?,
            traffic: CharSequence?,
            playing: Boolean,
            fullscreen: Boolean,
        ): PlaybackOverlayState {
            if (player == null) return PlaybackOverlayState("", false, false, 0)
            val info = join(player.getSizeText(), player.getCodecInfo(), player.getDecodeText(), traffic?.toString() ?: "")
            return PlaybackOverlayState(
                info,
                shouldShowVideoInfo(PlayerSettings.getVideoInfo(), fullscreen),
                PlayerSettings.showVideoClock(),
                if (playing) VideoNetworkPolicy.activeWarningMessage() else 0,
            )
        }

        /** The info line only appears during full-screen playback and never in the preview window. */
        @JvmStatic fun shouldShowVideoInfo(videoInfoMode: Int, fullscreen: Boolean): Boolean =
            PlayerSettings.videoInfoEnabledFor(videoInfoMode) && fullscreen

        @JvmStatic fun join(vararg values: String?): String {
            val result = StringBuilder()
            for (value in values) {
                if (value == null || value.all { Character.isWhitespace(it) }) continue
                if (result.isNotEmpty()) result.append(" | ")
                result.append(value)
            }
            return if (result.isEmpty()) ResUtil.getString(R.string.video_info_unknown) else result.toString()
        }
    }
}
