package com.wuming.android.tv.utils

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.wuming.android.tv.impl.Callback
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference

/** UI callback retained until completion, explicit close or owner destruction. Use on main. */
class LifecycleCallback private constructor(
    lifecycle: Lifecycle,
    callback: Callback?,
    private val once: Boolean,
) : Callback(),
    DefaultLifecycleObserver,
    AutoCloseable {
    private val lifecycle = WeakReference(lifecycle)
    private val delegate = AtomicReference(callback)

    init {
        if (lifecycle.currentState == Lifecycle.State.DESTROYED) delegate.set(null) else lifecycle.addObserver(this)
    }

    companion object {
        @JvmStatic fun wrap(
            owner: LifecycleOwner,
            callback: Callback?,
        ): Callback = LifecycleCallback(owner.lifecycle, callback, true)

        @JvmStatic fun wrapRepeated(
            owner: LifecycleOwner,
            callback: Callback?,
        ): Callback = LifecycleCallback(owner.lifecycle, callback, false)
    }

    private fun take(): Callback? {
        val callback = delegate.getAndSet(null)
        lifecycle.get()?.removeObserver(this)
        lifecycle.clear()
        return callback
    }

    override fun onDestroy(owner: LifecycleOwner) {
        close()
    }

    override fun close() {
        take()
    }

    private fun complete(): Callback? = if (once) take() else delegate.get()

    override fun start() {
        delegate.get()?.start()
    }

    override fun success() {
        complete()?.success()
    }

    override fun success(result: String) {
        complete()?.success(result)
    }

    override fun error() {
        complete()?.error()
    }

    override fun error(msg: String) {
        complete()?.error(msg)
    }
}
