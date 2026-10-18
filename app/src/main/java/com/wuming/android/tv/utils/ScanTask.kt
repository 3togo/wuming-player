package com.wuming.android.tv.utils

import com.wuming.android.tv.App
import com.wuming.android.tv.bean.Device
import com.wuming.android.tv.server.Server
import com.github.catvod.net.OkHttp
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Response
import java.net.URI
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Supplier

class ScanTask internal constructor(
    private var listener: Listener?,
    private val worker: Executor,
    private val main: Executor,
    private val remove: Consumer<Runnable>,
    private val localAddress: Supplier<String>,
    private val calls: Function<String, Call>,
    private val decode: Function<String, Device?>
) {

    interface Listener {
        fun onFind(device: Device)
    }

    constructor(listener: Listener) : this(
        object : Listener {
            override fun onFind(device: Device) {
                listener.onFind(device.save())
            }
        },
        Task.largeExecutor(),
        { r: Runnable -> App.post(r) },
        { r: Runnable -> App.removeCallbacks(r) },
        Supplier { Server.get().getAddress() },
        defaultCalls(),
        Function { json: String -> Device.objectFrom(json) }
    )

    private var current: Run? = null

    fun start() {
        startRun(null)
    }

    fun start(url: String?) {
        if (!url.isNullOrBlank()) startRun(url)
    }

    private fun startRun(address: String?) {
        val run: Run
        synchronized(this) {
            if (listener == null) return
            cancelCurrent()
            current = Run().also { run = it }
        }
        submit(run) {
            val local = localAddress.get()
            val urls = if (address == null) addresses(local) else listOf(address)
            synchronized(this) {
                if (current !== run) return@submit
                run.urls = urls.stream().filter { it != local }.toList()
            }
            for (i in 0 until kotlin.math.min(PARALLELISM, urls.size)) submit(run) { scan(run) }
        }
    }

    private fun submit(run: Run, action: Runnable) {
        val task = object : FutureTask<Void>({
            try {
                action.run()
            } catch (error: RuntimeException) {
                AppLog.w("ScanTask", "Discovery failed", error)
            }
        }, null) {
            override fun done() {
                synchronized(this@ScanTask) { run.tasks.remove(this) }
            }
        }
        synchronized(this) {
            if (current !== run) return
            run.tasks.add(task)
        }
        try {
            worker.execute(task)
        } catch (rejected: RuntimeException) {
            task.cancel(false)
        }
    }

    private fun scan(run: Run) {
        while (!Thread.currentThread().isInterrupted) {
            val url: String
            synchronized(this) {
                if (current !== run || run.next >= run.urls.size) return
                url = run.urls[run.next++]
            }
            var call: Call? = null
            try {
                call = calls.apply(url)
                synchronized(this) {
                    if (current !== run) {
                        call.cancel()
                        return
                    }
                    run.calls.add(call)
                }
                val device: Device?
                call.execute().use { response ->
                    val json = HttpResponseText.read(response, 64 * 1024)
                    device = decode.apply(json)
                }
                if (device != null) publish(run, device)
            } catch (ignored: Exception) {
            } finally {
                synchronized(this) { if (call != null) run.calls.remove(call) }
            }
        }
    }

    @Synchronized
    private fun publish(run: Run, device: Device) {
        if (current !== run || listener == null) return
        val delivery = object : Runnable {
            override fun run() {
                synchronized(this@ScanTask) {
                    run.deliveries.remove(this)
                    if (current === run && listener != null) listener!!.onFind(device)
                }
            }
        }
        run.deliveries.add(delivery)
        main.execute(delivery)
    }

    @Synchronized
    fun stop() {
        listener = null
        cancelCurrent()
    }

    @Synchronized
    private fun cancelCurrent() {
        val run = current ?: return
        current = null
        for (call in run.calls) call.cancel()
        for (task in ArrayList(run.tasks)) task.cancel(true)
        for (delivery in run.deliveries) remove.accept(delivery)
        run.tasks.clear()
        run.calls.clear()
        run.deliveries.clear()
        run.urls = emptyList()
    }

    private class Run {
        var urls: List<String> = emptyList()
        var next: Int = 0
        val tasks: MutableSet<FutureTask<*>> = HashSet()
        val calls: MutableSet<Call> = HashSet()
        val deliveries: MutableSet<Runnable> = HashSet()
    }

    companion object {
        private const val PARALLELISM = 8

        private fun defaultCalls(): Function<String, Call> {
            val client: OkHttpClient = OkHttp.client(1000)
            return Function { url: String -> OkHttp.newCall(client, "$url/device") }
        }

        @JvmStatic
        fun addresses(local: String?): List<String> {
            return try {
                val host = URI.create(local).host
                if (host == null || !host.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))) return emptyList()
                for (part in host.split(".")) if (part.toInt() > 255) return emptyList()
                val base = "http://" + host.substring(0, host.lastIndexOf('.') + 1)
                val urls = ArrayList<String>(255)
                for (i in 1..255) urls.add("$base$i:9978")
                urls
            } catch (invalid: RuntimeException) {
                emptyList()
            }
        }
    }
}
