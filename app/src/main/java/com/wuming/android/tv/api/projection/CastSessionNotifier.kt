package com.wuming.android.tv.api.projection

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.wuming.android.tv.App
import com.wuming.android.tv.ProductCore
import com.wuming.android.tv.ProductCore.Companion.getDeviceType
import com.wuming.android.tv.api.projection.ProjectionManager.CastSessionState
import com.wuming.android.tv.utils.AppLog
import com.wuming.android.tv.utils.AppLog.w

/**
 * Bridges shared cast state to the mobile-only foreground notification service.
 *
 *
 * The service class is resolved by name so the shared source set remains
 * compilable for Leanback, which intentionally has no casting controller.
 */
object CastSessionNotifier {
    private const val TAG = "CastSessionNotifier"
    private const val SERVICE_CLASS = "com.wuming.android.tv.service.CastSessionService"
    private const val ACTION_UPDATE = "com.wuming.android.tv.cast.UPDATE"
    private const val EXTRA_STATE = "state"
    private const val EXTRA_EPISODE = "episode"
    private const val EXTRA_DEVICE = "device"
    private const val EXTRA_RELAY_DEPENDENT = "relayDependent"

    @JvmStatic
    fun publish(state: CastSessionState, relayDependent: Boolean) {
        if (getDeviceType() != 1) return
        val context: Context = App.get()
        val intent: Intent = Intent().setClassName(context, SERVICE_CLASS)
        if (!state.isActive()) {
            context.stopService(intent)
            return
        }
        intent.setAction(ACTION_UPDATE)
            .putExtra(EXTRA_STATE, state.state().name)
            .putExtra(EXTRA_EPISODE, state.episodeName())
            .putExtra(EXTRA_DEVICE, state.deviceName())
            .putExtra(EXTRA_RELAY_DEPENDENT, relayDependent)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: RuntimeException) {
            w(TAG, "Unable to publish cast notification", e)
        }
    }
}
