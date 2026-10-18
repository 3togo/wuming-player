package com.wuming.android.tv.utils

import com.wuming.android.tv.App
import com.wuming.tv.core.design.update.UpdateArtifact
import okhttp3.Call
import java.io.File
import java.net.URI

/** One lifecycle-owned attempt chain. Every mirror must pass the same APK verifier. */
class UpdateDownload @JvmOverloads constructor(
    private val artifact: UpdateArtifact,
    private val file: File,
    private val calls: Call.Factory = UpdateClient.apkHttp,
) {
    private var current: Download? = null
    private var callback: Download.Callback? = null
    private var next = 0
    private var generation = 0

    @Synchronized fun start(target: Download.Callback) {
        cancel()
        callback = target
        next = 0
        attempt(generation, "No update download mirrors")
    }

    @Synchronized fun cancel() {
        generation++
        callback = null
        current?.cancel()
        current = null
    }

    @Synchronized private fun attempt(run: Int, lastError: String) {
        if (run != generation || callback == null) return
        if (next >= artifact.urls.size) {
            val target = callback
            callback = null
            current = null
            target?.error(lastError)
            return
        }
        val url = artifact.urls[next++]
        AppLog.d("UpdateDownload", "Attempt $next/${artifact.urls.size}: ${URI(url).host}, version=${artifact.code}")
        callback?.progress(0)
        current = Download.create(url, file, calls).also { download ->
            download.start(object : Download.Callback {
                override fun progress(value: Int) = deliverProgress(run, value)
                override fun error(message: String) = failed(run, url, message)
                override fun success(result: File) {
                    Task.largeExecutor().execute {
                        try {
                            UpdateApkVerifier.verify(result, artifact)
                            App.post { completed(run, url, result) }
                        } catch (error: Exception) {
                            App.post { failed(run, url, error.message ?: "APK verification failed") }
                        }
                    }
                }
            })
        }
    }

    @Synchronized private fun deliverProgress(run: Int, value: Int) {
        if (run == generation) callback?.progress(value)
    }

    @Synchronized private fun failed(run: Int, url: String, message: String) {
        if (run != generation || callback == null) return
        AppLog.w("UpdateDownload", "Mirror failed: ${URI(url).host}: $message")
        attempt(run, message)
    }

    @Synchronized private fun completed(run: Int, url: String, result: File) {
        if (run != generation || callback == null) return
        UpdateClient.remember(url)
        AppLog.d("UpdateDownload", "Verified: ${URI(url).host}, version=${artifact.code}")
        val target = callback
        callback = null
        current = null
        target?.success(result)
    }
}
