package com.wuming.android.tv.ui.base

import com.wuming.android.tv.App
import com.wuming.android.tv.bean.History
import com.wuming.android.tv.bean.Vod
import com.wuming.android.tv.library.UserLibrary
import com.wuming.android.tv.utils.Task
import java.lang.ref.WeakReference
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.function.Consumer

/** Owns background history restoration and the lifetime of its pending UI result. */
@JvmSuppressWildcards
class VideoHistoryLoader(
    host: Host?,
    private val store: Store,
    private val worker: Executor,
    private val main: Executor,
    private val remove: Consumer<Runnable>,
    private val reportError: Consumer<RuntimeException>,
) : AutoCloseable {
    interface Host {
        fun activeKey(): String?

        fun restored(
            key: String,
            mark: String?,
            item: Vod,
            history: History,
            existing: Boolean,
        )
    }

    interface Store {
        fun find(key: String): History?

        fun restoreEpisode(
            history: History,
            item: Vod,
        )
    }

    private var host: Host? = host
    private var pending: Request? = null
    constructor(host: Host?) : this(
        host,
        object : Store {
            override fun find(key: String): History? = UserLibrary.get().findHistory(key)

            override fun restoreEpisode(
                history: History,
                item: Vod,
            ) {
                UserLibrary.get().findEpisode(history, item.getFlags())
            }
        },
        Task.executor(),
        Executor {
            App.post(it)
        },
        Consumer { App.removeCallbacks(it) },
        Consumer { android.util.Log.e("VideoHistoryLoader", "History restoration failed", it) },
    )

    fun load(
        key: String,
        catalogId: Int,
        mark: String?,
        item: Vod,
    ) {
        val request: Request
        synchronized(this) {
            cancel()
            if (host == null) return
            request = Request(this, key, mark, item)
            val repository = store
            val errors = reportError
            request.task =
                FutureTask(
                    java.util.concurrent.Callable<Void?> {
                        var history: History? = null
                        try {
                            history = repository.find(key)
                        } catch (e: RuntimeException) {
                            errors.accept(e)
                        }
                        if (request.task.isCancelled) return@Callable null
                        val existing = history != null
                        if (history == null) {
                            history = History()
                            history.setKey(key)
                            history.setCid(catalogId)
                            history.setVodName(item.name)
                            try {
                                repository.restoreEpisode(history, item)
                            } catch (e: RuntimeException) {
                                errors.accept(e)
                            }
                        }
                        request.owner.get()?.complete(request, history, existing)
                        null
                    },
                )
            pending = request
        }
        worker.execute(request.task)
    }

    @Synchronized private fun complete(
        request: Request,
        history: History,
        existing: Boolean,
    ) {
        if (pending !== request || host == null) return
        request.history = history
        request.existing = existing
        main.execute(request)
    }

    @Synchronized private fun deliver(request: Request) {
        val host = host
        if (pending !== request || host == null) return
        pending = null
        if (request.key == host.activeKey()) host.restored(request.key, request.mark, request.item, request.history!!, request.existing)
    }

    @Synchronized fun cancel() {
        val request = pending
        pending = null
        if (request == null) return
        request.task.cancel(true)
        remove.accept(request)
        request.history = null
    }

    @Synchronized override fun close() {
        cancel()
        host = null
    }

    private class Request(
        owner: VideoHistoryLoader,
        val key: String,
        val mark: String?,
        val item: Vod,
    ) : Runnable {
        val owner = WeakReference(owner)
        lateinit var task: FutureTask<Void?>
        var history: History? = null
        var existing = false

        override fun run() {
            owner.get()?.deliver(this)
        }
    }
}
