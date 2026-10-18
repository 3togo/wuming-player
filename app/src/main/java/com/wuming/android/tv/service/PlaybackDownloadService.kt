package com.wuming.android.tv.service

import android.app.Notification
import androidx.annotation.Nullable
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import com.wuming.android.tv.R
import com.wuming.android.tv.player.offline.OfflinePlaybackStore

/** Foreground host for explicit, resumable Media3 downloads.  */
@UnstableApi
class PlaybackDownloadService :
    DownloadService(
        NOTIFICATION_ID,
        1000L,
        CHANNEL_ID,
        R.string.offline_download_channel,
        R.string.offline_download_channel_description
    ) {
    private var notificationHelper: DownloadNotificationHelper? = null

    override fun getDownloadManager(): DownloadManager = OfflinePlaybackStore.downloadManager()

    override fun getScheduler(): Scheduler? = PlatformScheduler(this, SCHEDULER_JOB_ID)

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int
    ): Notification {
        if (notificationHelper == null) {
            notificationHelper = DownloadNotificationHelper(this, CHANNEL_ID)
        }
        return notificationHelper!!.buildProgressNotification(
            this,
            R.drawable.ic_notification,
            null,
            null,
            downloads,
            notMetRequirements
        )
    }

    companion object {
        private const val CHANNEL_ID = "offline_playback"
        private const val NOTIFICATION_ID = 2002
        private const val SCHEDULER_JOB_ID = 2003
    }
}
