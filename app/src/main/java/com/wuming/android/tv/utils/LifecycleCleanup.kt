package com.wuming.android.tv.utils

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import java.lang.ref.WeakReference

/** Main-thread registration for resources which may finish before their owner. */
class LifecycleCleanup(
    owner: LifecycleOwner,
    private var cleanup: Runnable?
) : DefaultLifecycleObserver, AutoCloseable {
    private val lifecycle: WeakReference<Lifecycle> = WeakReference(owner.lifecycle)

    init {
        val value = owner.lifecycle
        if (value.currentState == Lifecycle.State.DESTROYED) onDestroy(owner)
        else value.addObserver(this)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        val action = cleanup
        close()
        action?.run()
    }

    /** Resource already finished: drop its callback and lifecycle registration. */
    override fun close() {
        cleanup = null
        val value = lifecycle.get()
        lifecycle.clear()
        value?.removeObserver(this)
    }
}
