package com.wuming.android.tv.utils

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.wuming.android.tv.App

/** A delayed handoff may outlive its dialog, but never the destination activity. */
object LifecycleDelayedAction {
    fun post(owner: LifecycleOwner, delayMillis: Long, action: Runnable) {
        schedule(owner.lifecycle, action, { App.post(it, delayMillis) }, { App.removeCallbacks(it) })
    }

    internal fun schedule(lifecycle: Lifecycle, action: Runnable, post: (Runnable) -> Unit, remove: (Runnable) -> Unit) {
        if (lifecycle.currentState == Lifecycle.State.DESTROYED) return
        var finished = false
        lateinit var observer: LifecycleEventObserver
        val callback = Runnable {
            if (!finished) {
                finished = true
                lifecycle.removeObserver(observer)
                if (lifecycle.currentState != Lifecycle.State.DESTROYED) action.run()
            }
        }
        observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY && !finished) {
                finished = true
                remove(callback)
                lifecycle.removeObserver(observer)
            }
        }
        lifecycle.addObserver(observer)
        if (!finished) post(callback)
    }
}
