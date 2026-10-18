package com.wuming.android.tv.utils

import android.content.Intent
import android.os.CountDownTimer
import com.wuming.android.tv.App
import com.wuming.android.tv.event.ActionEvent
import com.wuming.android.tv.service.PlaybackService
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit

open class Timer {
    private var timer: CountDownTimer? = null
    private var callbackRef: WeakReference<Callback>? = null
    private var tick = 0L

    private object Loader {
        @Volatile var instance = Timer()
    }

    companion object {
        @JvmStatic fun get(): Timer = Loader.instance
    }

    open fun isRunning(): Boolean = timer != null

    open fun setCallback(callback: Callback?) {
        callbackRef = if (callback != null) WeakReference(callback) else null
    }

    open fun getTick(): Long = tick

    open fun set(t: Long) {
        cancel()
        timer =
            object : CountDownTimer(t, 1000) {
                override fun onTick(tick: Long) {
                    this@Timer.onTick(tick)
                }

                override fun onFinish() {
                    this@Timer.onFinish()
                }
            }.start()
    }

    private fun onTick(tick: Long) {
        this.tick = tick
        callbackRef?.get()?.onTick(tick)
    }

    private fun onFinish() {
        callbackRef?.get()?.onFinish()
        if (PlaybackService.isRunning()) sendPause()
        reset()
    }

    private fun sendPause() {
        val intent = Intent(App.get(), PlaybackService::class.java)
        intent.action = ActionEvent.PAUSE
        App.get().startService(intent)
    }

    open fun delay() {
        cancel()
        set(TimeUnit.MINUTES.toMillis(5) + tick)
    }

    open fun reset() {
        tick = 0
        cancel()
        callbackRef = null
    }

    open fun cancel() {
        timer?.cancel()
        timer = null
    }

    interface Callback {
        fun onTick(tick: Long)

        fun onFinish()
    }
}
