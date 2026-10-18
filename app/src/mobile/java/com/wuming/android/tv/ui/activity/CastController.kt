package com.wuming.android.tv.ui.activity

import com.wuming.android.tv.utils.TextScale

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import com.wuming.android.tv.R
import com.wuming.android.tv.api.projection.ProjectionManager
import com.wuming.android.tv.bean.CastVideo
import com.wuming.android.tv.bean.Episode
import com.wuming.android.tv.bean.EpisodeShortName
import com.wuming.android.tv.bean.Flag
import com.wuming.android.tv.bean.History
import com.wuming.android.tv.bean.Track
import com.wuming.android.tv.databinding.ActivityVideoBinding
import com.wuming.android.tv.player.PlayerHelper
import com.wuming.android.tv.player.PlayerManager
import com.wuming.android.tv.ui.adapter.EpisodeAdapter
import com.wuming.android.tv.ui.dialog.CastDialogV2
import com.wuming.android.tv.utils.Notify
import com.wuming.android.tv.utils.ResUtil
import com.wuming.tv.core.playback.CastQueue
import com.google.android.material.textview.MaterialTextView
import java.util.ArrayList

/** Mobile cast status, sender session and deeplink audio-track replay. */
open class CastController(
    private val mHost: Host,
    private val mBinding: ActivityVideoBinding,
) {
    interface Host {
        fun getOwnerActivity(): FragmentActivity

        fun getPlayerManager(): PlayerManager?

        fun resolveVodName(): String

        fun resolveKey(): String

        fun resolveFlag(): Flag

        fun resolveEpisode(): Episode

        fun getHistory(): History?

        fun getEpisodeAdapter(): EpisodeAdapter

        fun getLaunchIntent(): Intent

        fun isFullscreenMode(): Boolean
    }

    private var mCastStatus: MaterialTextView? = null
    private var mCastAudioTrackApplied = false

    open fun createStatus() {
        val horizontal = ResUtil.getDimensionPixelSize(R.dimen.vs_8)
        val vertical = ResUtil.getDimensionPixelSize(R.dimen.vs_4)
        val background = GradientDrawable()
        background.setColor(mHost.getOwnerActivity().getColor(R.color.black_70))
        background.cornerRadius = ResUtil.getDimensionPixelSize(R.dimen.vs_8).toFloat()
        val status = MaterialTextView(mHost.getOwnerActivity())
        mCastStatus = status
        status.background = background
        status.ellipsize = TextUtils.TruncateAt.END
        status.gravity = Gravity.CENTER_VERTICAL
        status.maxLines = 1
        status.setPadding(horizontal, vertical, horizontal, vertical)
        status.setTextColor(mHost.getOwnerActivity().getColor(R.color.white))
        TextScale.setRole(status, com.wuming.tv.core.design.TextRole.MICRO)
        status.isClickable = true
        status.isFocusable = true
        status.setOnClickListener { onCast() }
        status.visibility = View.GONE
        val params =
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            )
        params.setMargins(
            ResUtil.getDimensionPixelSize(R.dimen.vs_12),
            ResUtil.getDimensionPixelSize(R.dimen.vs_64),
            ResUtil.getDimensionPixelSize(R.dimen.vs_12),
            0,
        )
        mBinding.video.addView(status, params)
    }

    open fun onSessionChanged(state: ProjectionManager.CastSessionState) {
        val status = mCastStatus ?: return
        if (!state.isActive()) {
            status.visibility = View.GONE
            mBinding.videoInfo.visibility =
                if (mHost.isFullscreenMode() && com.wuming.android.tv.setting.PlayerSettings.showVideoInfo()) View.VISIBLE else View.GONE
            mBinding.control.cast.contentDescription = mHost.getOwnerActivity().getString(R.string.cast)
            checkCastImg()
            return
        }
        val episode = if (state.episodeName().isEmpty()) (state.episodeIndex() + 1).toString() else state.episodeName()
        val suffix =
            when (state.state()) {
                ProjectionManager.CastState.CONNECTING, ProjectionManager.CastState.RESOLVING -> " …"
                ProjectionManager.CastState.ERROR -> " !"
                else -> ""
            }
        val compat = if (state.compatMode()) " · " + mHost.getOwnerActivity().getString(R.string.cast_compat_badge) else ""
        val text =
            mHost.getOwnerActivity().getString(R.string.cast) + ": " + episode + suffix + compat + " · " +
                mHost.getOwnerActivity().getString(R.string.device_disconnect)
        status.text = text
        status.contentDescription = mHost.getOwnerActivity().getString(R.string.device_connected, state.deviceName()) + ", " + text
        status.visibility = View.VISIBLE
        mBinding.videoInfo.visibility = View.GONE
        mBinding.control.cast.contentDescription = text
        checkCastImg()
    }

    open fun checkCastImg() {
        mBinding.control.cast.setImageResource(
            if (ProjectionManager.get().getConnectedDevice() !=
                null
            ) {
                R.drawable.ic_control_cast_on
            } else {
                R.drawable.ic_control_cast
            },
        )
    }

    open fun onCast() {
        val player = mHost.getPlayerManager() ?: return
        player.pause()
        if (ProjectionManager.get().getConnectedDevice() != null) {
            ProjectionManager.get().disconnect()
            checkCastImg()
            Notify.show(R.string.device_disconnect)
        } else {
            val queueItems = ArrayList<CastQueue.Item>()
            val vodName = mBinding.name.text.toString()
            val vodContext = mHost.resolveVodName()
            for (episode in mHost.getEpisodeAdapter().getItems()) {
                queueItems.add(CastQueue.Item(EpisodeShortName.shorten(episode.getName(), vodContext), episode.getUrl()))
            }
            val queue =
                CastQueue(
                    queueItems,
                    mHost.getEpisodeAdapter().getPosition(),
                    mHost.resolveKey(),
                    mHost.resolveFlag().getFlag(),
                    player.getSelectedAudioTrackFormat(),
                )
            val nextEpisode = mHost.getEpisodeAdapter().getNext()
            var nextName: String? = null
            var nextUrl: String? = null
            if (nextEpisode != null && !nextEpisode.isActivated() && nextEpisode.getUrl().isNotEmpty()) {
                nextName = EpisodeShortName.shorten(nextEpisode.getName(), vodContext)
                nextUrl = nextEpisode.getUrl()
            }
            val episodeName = EpisodeShortName.shorten(mHost.resolveEpisode().getName(), vodContext)
            val castTitle =
                if (!episodeName.isNullOrEmpty()) {
                    mHost.getOwnerActivity().getString(
                        R.string.detail_title,
                        vodName,
                        episodeName,
                    )
                } else {
                    vodName
                }
            val video =
                CastVideo(
                    castTitle,
                    player.getUrl(),
                    player.getPosition(),
                    player.getDuration(),
                    player.getHeaders(),
                    nextName,
                    nextUrl,
                    mHost.resolveKey(),
                    mHost.resolveFlag().getFlag(),
                    player.getSelectedAudioTrackFormat(),
                )
            CastDialogV2
                .create()
                .history(mHost.getHistory())
                .video(video)
                .queue(queue)
                .player(mHost.getPlayerManager())
                .fm(true)
                .onCasted {
                    mHost.getPlayerManager()?.pause()
                    checkCastImg()
                }.show(mHost.getOwnerActivity())
        }
    }

    open fun onTracksChanged() {
        applyCastAudioTrack()
    }

    private fun applyCastAudioTrack() {
        if (mCastAudioTrackApplied) return
        val audioTrackFormat = mHost.getLaunchIntent().getStringExtra("audioTrackFormat") ?: return
        val player = mHost.getPlayerManager() ?: return
        if (player.isEmpty()) return
        mCastAudioTrackApplied = true
        val tracks = player.getCurrentTracks()
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                if (audioTrackFormat == PlayerHelper.describeFormat(format)) {
                    val track = Track(C.TRACK_TYPE_AUDIO, format.label ?: "", audioTrackFormat)
                    track.isSelected = true
                    track.key(player.getKey())
                    player.setTrack(java.util.Arrays.asList(track))
                    return
                }
            }
        }
    }

    open fun release() {
        mCastStatus = null
    }
}
