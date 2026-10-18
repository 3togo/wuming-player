package com.wuming.android.tv.ui.activity

import androidx.fragment.app.FragmentActivity
import com.wuming.android.tv.R
import com.wuming.android.tv.api.projection.ProjectionManager
import com.wuming.android.tv.bean.CastVideo
import com.wuming.android.tv.bean.Episode
import com.wuming.android.tv.bean.EpisodeShortName
import com.wuming.android.tv.bean.Flag
import com.wuming.android.tv.bean.History
import com.wuming.android.tv.databinding.ActivityVideoBinding
import com.wuming.android.tv.player.PlayerManager
import com.wuming.android.tv.ui.dialog.CastDialogV2
import com.wuming.android.tv.utils.Notify

/** Sender flow; the activity retains the cross-flavor cast entry points. */
open class CastController(
    private val mHost: Host,
    private val mBinding: ActivityVideoBinding,
) {
    interface Host {
        fun getOwnerActivity(): FragmentActivity

        fun getPlayerManager(): PlayerManager

        fun getHistory(): History?

        fun getKey(): String

        fun getVodName(): String

        fun getFlag(): Flag

        fun getNextEpisode(): Episode?

        fun hideControl()
    }

    open fun onCast() {
        // Pause even during buffering so local cloud loading cannot compete with casting.
        mHost.getPlayerManager().pause()
        if (ProjectionManager.get().getConnectedDevice() != null) {
            ProjectionManager.get().disconnect()
            checkCastImg()
            Notify.show(R.string.device_disconnect)
        } else {
            val nextEpisode = mHost.getNextEpisode()
            var nextName: String? = null
            var nextUrl: String? = null
            if (nextEpisode != null && !nextEpisode.isActivated() && nextEpisode.getUrl().isNotEmpty()) {
                nextName = EpisodeShortName.shorten(nextEpisode.getName(), mHost.getVodName())
                nextUrl = nextEpisode.getUrl()
            }
            CastDialogV2
                .create()
                .history(mHost.getHistory())
                .video(
                    CastVideo(
                        mBinding.name.text.toString(),
                        mHost.getPlayerManager().getUrl(),
                        mHost.getPlayerManager().getPosition(),
                        mHost.getPlayerManager().getDuration(),
                        mHost.getPlayerManager().getHeaders(),
                        nextName,
                        nextUrl,
                        mHost.getKey(),
                        mHost.getFlag().getFlag(),
                        mHost.getPlayerManager().getSelectedAudioTrackFormat(),
                    ),
                ).player(mHost.getPlayerManager())
                .onCasted {
                    mHost.getPlayerManager().pause()
                    checkCastImg()
                }.show(mHost.getOwnerActivity())
        }
        mHost.hideControl()
    }

    open fun checkCastImg() {
        mBinding.control.action.cast.isActivated = ProjectionManager.get().getConnectedDevice() != null
    }
}
