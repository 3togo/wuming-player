package com.wuming.tv.core.base

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Callable
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.function.BiFunction
import java.util.function.Consumer
import okhttp3.Call
import okhttp3.Request
import okhttp3.ResponseBody

/** Each run owns its HTTP call, staging file and removable UI deliveries. */
open class Download(
    private val url: String,
    file: File,
    private val worker: Executor,
    private val main: Executor,
    private val remove: Consumer<Runnable>,
    private val calls: BiFunction<String, String?, Call>
) {
    private val file = file.absoluteFile
    private var tag: String? = url
    private var current: Run? = null

    @Synchronized
    open fun tag(tag: String?): Download { this.tag = tag; return this }

    open fun get(): File {
        val run = begin(null)
        try {
            transfer(run)
            return file
        } catch (error: IOException) {
            throw RuntimeException(error.message, error)
        } finally {
            synchronized(this) { if (current === run) current = null }
        }
    }

    open fun start(callback: Callback?) {
        val run = begin(callback)
        worker.execute(run.work!!)
    }

    @Synchronized
    private fun begin(callback: Callback?): Run {
        cancel()
        val run = Run(callback)
        current = run
        run.work = FutureTask<Void?>(Callable {
            try {
                transfer(run)
                finish(run, null)
            } catch (error: Exception) {
                finish(run, error.message ?: error.javaClass.simpleName)
            }
            null
        })
        return run
    }

    @Synchronized
    open fun cancel(): Download {
        val run = current
        current = null
        if (run == null) return this
        run.callback = null
        remove.accept(run.progressDelivery)
        remove.accept(run.completionDelivery)
        run.call?.cancel()
        run.work?.cancel(true)
        // The worker deletes its staging file. Never delete a committed destination.
        return this
    }

    @Throws(IOException::class)
    private fun transfer(run: Run) {
        var staging: File? = null
        try {
            val call = synchronized(this) {
                requireCurrent(run)
                calls.apply(url, tag).also { run.call = it }
            }
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP " + response.code)
                val body = requireBody(response.body)
                val parent = file.parentFile!!
                if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Unable to create download directory")
                val part = File.createTempFile("download-", ".part", parent)
                staging = part
                val length = body.contentLength()
                var total = 0L
                var last = -1
                body.byteStream().use { input ->
                    FileOutputStream(part).use { output ->
                        val buffer = ByteArray(16384)
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            synchronized(this) { requireCurrent(run) }
                            output.write(buffer, 0, count)
                            total += count
                            val progress = if (length > 0) minOf(100.0, total * 100.0 / length).toInt() else -1
                            if (progress >= 0 && progress != last) {
                                last = progress
                                progress(run, progress)
                            }
                        }
                        if (length >= 0 && total != length) throw IOException("Incomplete download")
                        output.fd.sync()
                    }
                }
                synchronized(this) {
                    requireCurrent(run)
                    Files.move(part.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        } finally {
            staging?.let { Files.deleteIfExists(it.toPath()) }
            synchronized(this) { run.call = null }
        }
    }

    private fun requireBody(body: ResponseBody?): ResponseBody = body ?: throw IOException("Empty response body")

    @Throws(InterruptedIOException::class)
    private fun requireCurrent(run: Run) {
        if (current !== run || Thread.currentThread().isInterrupted) throw InterruptedIOException("Download canceled")
    }

    @Synchronized
    private fun progress(run: Run, value: Int) {
        if (current !== run || run.callback == null) return
        run.progress = value
        remove.accept(run.progressDelivery)
        main.execute(run.progressDelivery)
    }

    @Synchronized
    private fun finish(run: Run, error: String?) {
        if (current !== run) return
        run.error = error
        run.work = null
        if (run.callback == null) current = null else main.execute(run.completionDelivery)
    }

    private inner class Run(var callback: Callback?) {
        var call: Call? = null
        var work: FutureTask<*>? = null
        var progress = 0
        var error: String? = null
        val progressDelivery = Runnable {
            synchronized(this@Download) {
                if (current === this) callback?.progress(progress)
            }
        }
        val completionDelivery = Runnable {
            synchronized(this@Download) {
                if (current === this) {
                    val target = callback
                    callback = null
                    current = null
                    remove.accept(progressDelivery)
                    if (target != null) {
                        val failure = error
                        if (failure == null) target.success(file) else target.error(failure)
                    }
                }
            }
        }
    }

    interface Callback {
        fun progress(value: Int)
        fun error(message: String)
        fun success(result: File)
    }

}
