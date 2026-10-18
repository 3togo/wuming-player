package com.wuming.android.tv.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.annotation.Nullable
import androidx.core.app.NotificationCompat
import com.wuming.android.tv.App
import com.wuming.android.tv.R
import com.wuming.android.tv.api.projection.ProjectionManager
import com.wuming.android.tv.ui.activity.HomeActivity

class CastSessionService : Service() {

    private var relayDependent: Boolean = false
    private val relayCheck = Runnable { checkRelayActivity() }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (ACTION_DISCONNECT == action) {
            ProjectionManager.get().disconnect()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (ACTION_UPDATE != action) {
            stopSelf()
            return START_NOT_STICKY
        }
        val state = intent.getStringExtra(EXTRA_STATE)
        val episode = safe(intent.getStringExtra(EXTRA_EPISODE))
        val device = safe(intent.getStringExtra(EXTRA_DEVICE))
        relayDependent = intent.getBooleanExtra(EXTRA_RELAY_DEPENDENT, false)
        startForeground(NOTIFICATION_ID, buildNotification(state, episode, device))
        if (relayDependent) App.post(relayCheck, RELAY_CHECK_MILLIS)
        else App.removeCallbacks(relayCheck)
        return START_NOT_STICKY
    }

    private fun checkRelayActivity() {
        if (!relayDependent) return
        if (ProjectionManager.get().expireInactiveRemoteCast(
                System.currentTimeMillis(), RELAY_IDLE_MILLIS
            )) {
            relayDependent = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        App.post(relayCheck, RELAY_CHECK_MILLIS)
    }

    private fun buildNotification(state: String?, episode: String, device: String): Notification {
        val homeIntent = Intent(this, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val contentIntent = PendingIntent.getActivity(
            this, 0, homeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val disconnectIntent = Intent(this, CastSessionService::class.java)
            .setAction(ACTION_DISCONNECT)
        val disconnect = PendingIntent.getService(
            this, 1, disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.cast_notification_title, device))
            .setContentText(getStatusText(state, episode))
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.device_disconnect), disconnect)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun getStatusText(state: String?, episode: String): String {
        if (ProjectionManager.CastState.RESOLVING.name == state) {
            return getString(R.string.cast_notification_resolving, episode)
        }
        if (ProjectionManager.CastState.PLAYING.name == state) {
            return getString(R.string.cast_notification_playing, episode)
        }
        if (ProjectionManager.CastState.ERROR.name == state) {
            return getString(R.string.cast_notification_error, episode)
        }
        return getString(R.string.cast_notification_connecting, episode)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL, getString(R.string.cast_notification_channel), NotificationManager.IMPORTANCE_LOW
        )
        channel.description = getString(R.string.cast_notification_channel)
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    @Nullable
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        App.removeCallbacks(relayCheck)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "cast_session"
        private const val NOTIFICATION_ID = 2307
        private const val ACTION_UPDATE = "com.wuming.android.tv.cast.UPDATE"
        private const val ACTION_DISCONNECT = "com.wuming.android.tv.cast.DISCONNECT"
        private const val EXTRA_STATE = "state"
        private const val EXTRA_EPISODE = "episode"
        private const val EXTRA_DEVICE = "device"
        private const val EXTRA_RELAY_DEPENDENT = "relayDependent"
        private const val RELAY_CHECK_MILLIS = 30_000L
        private const val RELAY_IDLE_MILLIS = 90_000L

        private fun safe(value: String?): String = value ?: ""
    }
}
