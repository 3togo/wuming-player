package com.wuming.tv.core.design.clock

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

class ClockTicker {

    @Volatile
    private var format: DateTimeFormatter? = null
    private val listeners = CopyOnWriteArrayList<TickListener>()
    @Volatile
    private var running = false

    fun format(pattern: String): ClockTicker {
        this.format = DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
        return this
    }

    fun addListener(listener: TickListener?) {
        if (listener != null) listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: TickListener) {
        listeners.remove(listener)
    }

    fun start() {
        if (running) return
        running = true
    }

    fun stop() {
        running = false
    }

    fun isRunning(): Boolean = running

    fun tick() {
        if (!running) return
        val time = System.currentTimeMillis()
        val formatted = format?.format(LocalDateTime.now())
        for (listener in listeners) {
            listener.onTick(time, formatted)
        }
    }

    fun interface TickListener {
        fun onTick(timeMillis: Long, formattedTime: String?)
    }

    companion object {
        @JvmStatic
        fun create(): ClockTicker = ClockTicker()
    }
}
