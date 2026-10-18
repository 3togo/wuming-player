package com.wuming.android.tv.utils

import android.annotation.TargetApi
import android.app.Activity
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.media3.ui.R
import com.wuming.android.tv.App
import com.wuming.android.tv.event.ActionEvent
import com.wuming.android.tv.receiver.ActionReceiver
import com.wuming.android.tv.setting.PlayerSettings
import com.wuming.android.tv.utils.AppLog
import java.util.ArrayList

open class PiP {
    private var builder: PictureInPictureParams.Builder? = if (noPiP()) null else PictureInPictureParams.Builder()

    companion object {
        private const val TAG = "PiP"

        @JvmStatic fun noPiP(): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                !App.get().packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun buildRemoteAction(
        activity: Activity,
        @DrawableRes icon: Int,
        @StringRes title: Int,
        action: String,
    ): RemoteAction =
        RemoteAction(
            Icon.createWithResource(activity, icon),
            activity.getString(title),
            "",
            ActionReceiver.getPendingIntent(activity, action),
        )

    private fun getPlayPauseAction(
        activity: Activity,
        play: Boolean,
    ): RemoteAction =
        if (play) {
            buildRemoteAction(
                activity,
                R.drawable.exo_icon_pause,
                R.string.exo_controls_pause_description,
                ActionEvent.PAUSE,
            )
        } else {
            buildRemoteAction(activity, R.drawable.exo_icon_play, R.string.exo_controls_play_description, ActionEvent.PLAY)
        }

    open fun update(
        activity: Activity,
        view: View,
    ) {
        try {
            if (noPiP()) return
            val rect = Rect()
            view.getGlobalVisibleRect(rect)
            builder!!.setSourceRectHint(rect)
            activity.setPictureInPictureParams(builder!!.build())
        } catch (e: Exception) {
            AppLog.e(TAG, e)
        }
    }

    open fun update(
        activity: Activity,
        play: Boolean,
    ) {
        try {
            if (noPiP()) return
            val actions = ArrayList<RemoteAction>()
            actions.add(
                buildRemoteAction(
                    activity,
                    com.wuming.android.tv.R.drawable.ic_action_audio,
                    R.string.exo_controls_hide,
                    ActionEvent.AUDIO,
                ),
            )
            actions.add(getPlayPauseAction(activity, play))
            actions.add(buildRemoteAction(activity, R.drawable.exo_icon_next, R.string.exo_controls_next_description, ActionEvent.NEXT))
            activity.setPictureInPictureParams(builder!!.setActions(actions).build())
        } catch (e: Exception) {
            AppLog.e(TAG, e)
        }
    }

    open fun enter(
        activity: Activity,
        width: Int,
        height: Int,
        scale: Int,
    ) {
        try {
            if (noPiP() || activity.isInPictureInPictureMode || !PlayerSettings.isBackgroundPiP()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder!!.setSeamlessResizeEnabled(true)
            builder!!.setAspectRatio(
                when (scale) {
                    1 -> Rational(16, 9)
                    2 -> Rational(4, 3)
                    else -> getRational(width, height)
                },
            )
            activity.enterPictureInPictureMode(builder!!.build())
        } catch (e: Exception) {
            AppLog.e(TAG, e)
        }
    }

    private fun getRational(
        width: Int,
        height: Int,
    ): Rational {
        if (width <= 0 || height <= 0) return Rational(16, 9)
        val limitWide = Rational(239, 100)
        val limitTall = Rational(100, 239)
        val rational = Rational(width, height)
        if (rational.isInfinite) return Rational(16, 9)
        if (rational.toFloat() > limitWide.toFloat()) return limitWide
        if (rational.toFloat() < limitTall.toFloat()) return limitTall
        return rational
    }
}
