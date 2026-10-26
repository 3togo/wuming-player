package com.wuming.tv.core.playback

import java.nio.charset.Charset

/** Keeps native tasks paired even when switching streams or a JNI call fails. */
class JianPianSession(private val native: Native) {
    interface Native {
        fun start(path: ByteArray): Int
        fun add(path: ByteArray): Int
        fun pause(path: ByteArray): Int
        fun delete(path: ByteArray): Int
    }

    var path: String? = null
        private set

    @Synchronized
    fun start(nextPath: String) {
        stop()
        val encoded = nextPath.toByteArray(GBK)
        path = nextPath
        try {
            checkResult("start", native.start(encoded))
            checkResult("add", native.add(encoded))
        } catch (failure: Throwable) {
            try {
                stop()
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    @Synchronized
    fun stop() {
        val current = path ?: return
        val encoded = current.toByteArray(GBK)
        var failure: Throwable? = null
        try {
            checkResult("pause", native.pause(encoded))
        } catch (error: Throwable) {
            failure = error
        }
        try {
            checkResult("delete", native.delete(encoded))
            path = null
        } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        failure?.let { throw it }
    }

    companion object {
        private val GBK = Charset.forName("GBK")

        // The vendor API does not document positive values; retain them as success/task IDs.
        private fun checkResult(operation: String, result: Int) {
            if (result < 0) throw IllegalStateException("JianPian native $operation failed: $result")
        }
    }
}
