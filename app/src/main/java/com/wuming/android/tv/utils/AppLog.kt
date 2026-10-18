package com.wuming.android.tv.utils

import android.util.Log
import com.wuming.android.tv.BuildConfig

object AppLog {
    enum class Level { VERBOSE, DEBUG, INFO, WARN, ERROR }

    fun interface LogSink {
        fun emit(level: Level, tag: String, message: String, error: Throwable?)
    }

    @Volatile
    private var minLevel: Level = if (BuildConfig.DEBUG) Level.VERBOSE else Level.WARN

    @Volatile
    private var sink: LogSink = AndroidLogSink

    @JvmStatic
    fun setSink(sink: LogSink?) {
        this.sink = sink ?: AndroidLogSink
    }

    @JvmStatic
    fun setMinLevel(level: Level) {
        minLevel = level
    }

    @JvmStatic
    fun getMinLevel(): Level = minLevel

    @JvmStatic
    fun v(tag: String, msg: String) {
        emit(Level.VERBOSE, tag, msg, null)
    }

    @JvmStatic
    fun d(tag: String, msg: String) {
        emit(Level.DEBUG, tag, msg, null)
    }

    @JvmStatic
    fun d(tag: String, msg: String, tr: Throwable) {
        emit(Level.DEBUG, tag, msg, tr)
    }

    @JvmStatic
    fun i(tag: String, msg: String) {
        emit(Level.INFO, tag, msg, null)
    }

    @JvmStatic
    fun w(tag: String, msg: String) {
        emit(Level.WARN, tag, msg, null)
    }

    @JvmStatic
    fun w(tag: String, tr: Throwable) {
        emit(Level.WARN, tag, safeMessage(tr), tr)
    }

    @JvmStatic
    fun w(tag: String, msg: String, tr: Throwable) {
        emit(Level.WARN, tag, msg, tr)
    }

    @JvmStatic
    fun e(tag: String, tr: Throwable) {
        emit(Level.ERROR, tag, safeMessage(tr), tr)
    }

    @JvmStatic
    fun e(tag: String, msg: String, tr: Throwable) {
        emit(Level.ERROR, tag, msg, tr)
    }

    @JvmStatic
    fun e(tag: String, msg: String) {
        emit(Level.ERROR, tag, msg, null)
    }

    @JvmStatic
    fun e(tr: Throwable) {
        emit(Level.ERROR, DEFAULT_TAG, safeMessage(tr), tr)
    }

    private const val DEFAULT_TAG = "TVBox"

    private fun emit(level: Level, tag: String, message: String, error: Throwable?) {
        if (level.ordinal < minLevel.ordinal) return
        try {
            sink.emit(level, tag, message, error)
        } catch (ignored: RuntimeException) {
        }
    }

    private fun safeMessage(tr: Throwable?): String {
        if (tr == null) return "Unknown error"
        val msg = tr.message
        return msg ?: tr.javaClass.simpleName
    }

    private object AndroidLogSink : LogSink {
        override fun emit(level: Level, tag: String, message: String, error: Throwable?) {
            try {
                when (level) {
                    Level.VERBOSE -> Log.v(tag, message)
                    Level.DEBUG -> if (error != null) Log.d(tag, message, error) else Log.d(tag, message)
                    Level.INFO -> Log.i(tag, message)
                    Level.WARN -> if (error != null) Log.w(tag, message, error) else Log.w(tag, message)
                    Level.ERROR -> if (error != null) Log.e(tag, message, error) else Log.e(tag, message)
                }
            } catch (ignored: RuntimeException) {
            }
        }
    }
}
