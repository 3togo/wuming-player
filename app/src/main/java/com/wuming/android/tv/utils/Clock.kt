package com.wuming.android.tv.utils

import android.os.Handler
import android.os.Looper
import android.widget.TextView
import com.wuming.tv.core.design.clock.ClockTicker
import java.lang.ref.WeakReference

open class Clock {
    private val ticker = ClockTicker.create()
    private var viewRef: WeakReference<TextView>? = null
    private var callback: Callback? = null
    private var handler: Handler? = null
    private var tickRunnable: Runnable? = null

    init {
        ticker.addListener(ViewTickListener(this))
    }

    companion object {
        @JvmStatic fun create(): Clock = Clock()

        @JvmStatic fun create(view: TextView?): Clock = Clock().view(view).format("HH:mm:ss")
    }

    open fun view(view: TextView?): Clock {
        viewRef = WeakReference(view)
        return this
    }

    open fun format(format: String): Clock {
        ticker.format(format)
        return this
    }

    open fun setCallback(callback: Callback?) {
        this.callback = callback
    }

    open fun start() {
        stop()
        handler = Handler(Looper.getMainLooper())
        tickRunnable = TickerPump(this)
        ticker.start()
        handler?.post(tickRunnable ?: return)
    }

    fun doTick(
        timeMillis: Long,
        formattedTime: String?,
    ) {
        try {
            val v = viewRef?.get()
            if (v != null && v.isShown && formattedTime != null && !formattedTime.contentEquals(v.text)) v.text = formattedTime
        } catch (e: Exception) {
            android.util.Log.d("Clock", "Failed to update clock display", e)
        }
        val current = callback
        if (current != null) {
            try {
                current.onTimeChanged(timeMillis)
            } catch (e: Exception) {
                android.util.Log.d("Clock", "Callback failed", e)
            }
        }
    }

    open fun stop(): Clock {
        ticker.stop()
        tickRunnable?.let { handler?.removeCallbacks(it) }
        tickRunnable = null
        handler?.removeCallbacksAndMessages(null)
        handler = null
        return this
    }

    open fun release() {
        stop()
        callback = null
        viewRef?.clear()
        viewRef = null
    }

    fun interface Callback {
        fun onTimeChanged(time: Long)
    }

    private class ViewTickListener(
        clock: Clock,
    ) : ClockTicker.TickListener {
        private val clockRef = WeakReference(clock)

        override fun onTick(
            timeMillis: Long,
            formattedTime: String?,
        ) {
            clockRef.get()?.doTick(timeMillis, formattedTime)
        }
    }

    private class TickerPump(
        clock: Clock,
    ) : Runnable {
        private val clockRef = WeakReference(clock)

        override fun run() {
            val clock = clockRef.get() ?: return
            if (!clock.ticker.isRunning() || clock.handler == null) return
            clock.ticker.tick()
            if (clock.ticker.isRunning()) clock.handler?.postDelayed(this, 1000)
        }
    }
}
