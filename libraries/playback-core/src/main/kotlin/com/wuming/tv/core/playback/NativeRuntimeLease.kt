package com.wuming.tv.core.playback

/** A release request waits for both playback and concurrent episode parsers to finish. */
class NativeRuntimeLease(private val releaseNative: () -> Unit) {
    private var users = 0
    private var releaseRequested = false

    @Synchronized
    fun acquire() {
        users++
    }

    @Synchronized
    fun release() {
        check(users > 0)
        users--
        releaseIfIdle()
    }

    @Synchronized
    fun requestRelease() {
        releaseRequested = true
        releaseIfIdle()
    }

    private fun releaseIfIdle() {
        if (!releaseRequested || users != 0) return
        releaseNative()
        releaseRequested = false
    }
}
