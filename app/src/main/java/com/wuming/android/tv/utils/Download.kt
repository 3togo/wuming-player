package com.wuming.android.tv.utils

import com.wuming.android.tv.App
import com.github.catvod.net.OkHttp
import java.io.File
import java.util.concurrent.Executor
import java.util.function.BiFunction
import java.util.function.Consumer
import okhttp3.Call
import okhttp3.Request

/** Android executors and HTTP configuration for the shared transfer lifecycle. */
open class Download(
    url: String, file: File, worker: Executor, main: Executor,
    remove: Consumer<Runnable>, calls: BiFunction<String, String?, Call>
) : com.wuming.tv.core.base.Download(url, file, worker, main, remove, calls) {
    constructor(url: String, file: File) : this(url, file, Task.largeExecutor(), Executor { App.post(it) },
        Consumer { App.removeCallbacks(it) }, BiFunction { address, tag -> OkHttp.newCall(address, tag) })
    override fun tag(tag: String?): Download { super.tag(tag); return this }
    override fun cancel(): Download { super.cancel(); return this }
    open fun start(callback: Callback?) = super.start(callback)
    interface Callback : com.wuming.tv.core.base.Download.Callback
    companion object {
        @JvmStatic fun create(url: String, file: File) = Download(url, file)
        @JvmStatic fun create(url: String, file: File, calls: Call.Factory) =
            Download(url, file, Task.largeExecutor(), Executor { App.post(it) }, Consumer { App.removeCallbacks(it) },
                BiFunction { address, tag -> calls.newCall(Request.Builder().url(address).tag(tag).build()) })
    }
}
