package com.wuming.android.tv.receiver

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wuming.android.tv.service.PlaybackService

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!PlaybackService.isRunning() || intent.action == null) return
        val serviceIntent = Intent(context, PlaybackService::class.java)
        serviceIntent.action = intent.action
        context.startService(serviceIntent)
    }

    companion object {
        @JvmStatic
        fun getPendingIntent(context: Context, action: String): PendingIntent =
            PendingIntent.getBroadcast(context, 0,
                Intent(action).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
