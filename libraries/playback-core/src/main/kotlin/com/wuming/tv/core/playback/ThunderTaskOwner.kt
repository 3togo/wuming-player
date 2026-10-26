package com.wuming.tv.core.playback

import java.util.concurrent.CancellationException

/** Serializes native task publication, probing, and stopping without locking during waits. */
class ThunderTaskOwner<T : Any>(
    private val begin: () -> Unit,
    private val end: () -> Unit,
    private val stopNative: (T) -> Unit,
) {
    private val lock = Any()
    private var current: T? = null

    fun start(create: () -> T): T = synchronized(lock) {
        stopLocked()
        begin()
        try {
            create().also { current = it }
        } catch (failure: Throwable) {
            try {
                end()
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    fun <R> withCurrent(task: T, probe: () -> R): R = synchronized(lock) {
        if (current !== task) throw CancellationException("Thunder task stopped")
        probe()
    }

    fun stop() = synchronized(lock) { stopLocked() }

    fun stopIfCurrent(task: T) = synchronized(lock) {
        if (current === task) stopLocked()
    }

    fun currentTask(): T? = synchronized(lock) { current }

    private fun stopLocked() {
        val task = current ?: return
        stopNative(task)
        current = null
        end()
    }
}
