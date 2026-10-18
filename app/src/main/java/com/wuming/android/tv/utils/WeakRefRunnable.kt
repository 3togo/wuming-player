package com.wuming.android.tv.utils

import android.app.Activity
import java.lang.ref.WeakReference
import java.util.function.Consumer

/**
 * A [Runnable] that holds a [WeakReference] to an [Activity] and invokes the
 * given action only while the activity is still alive and not destroyed. Use this instead of
 * hand-written per-class WeakReference Runnable wrappers to avoid leaking the Activity through a
 * stored callback.
 */
class WeakRefRunnable<T : Activity>(activity: T, private val action: Consumer<T>) : Runnable {
    private val ref = WeakReference(activity)

    override fun run() {
        val activity = ref.get() ?: return
        if (!activity.isDestroyed) {
            action.accept(activity)
        }
    }
}
