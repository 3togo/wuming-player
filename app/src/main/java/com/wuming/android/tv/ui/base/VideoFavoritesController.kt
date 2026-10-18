package com.wuming.android.tv.ui.base

import com.wuming.android.tv.App
import com.wuming.android.tv.bean.Keep
import com.wuming.android.tv.library.UserLibrary
import com.wuming.android.tv.utils.Task
import java.lang.ref.WeakReference
import java.util.concurrent.Executor
import java.util.function.Function

/** Favorite persistence and stale-result filtering without retaining screens in queued work. */
class VideoFavoritesController(
    host: Host?,
    private val store: Store,
    private val worker: Executor,
    private val main: Executor,
) : AutoCloseable {
    interface Host {
        fun activeKey(): String?

        fun showFavorite(
            kept: Boolean,
            notify: Boolean,
        )
    }

    interface Store {
        fun find(key: String): Keep?

        fun save(keep: Keep)

        fun delete(keep: Keep)
    }

    private var host: Host? = host
    private var generation = 0
    constructor(host: Host?) : this(host, defaultStore(), Task.executor(), App.mainThreadExecutor)

    companion object {
        private fun defaultStore(): Store =
            object : Store {
                override fun find(key: String): Keep? = UserLibrary.get().findFavorite(key)

                override fun save(keep: Keep) {
                    UserLibrary.get().saveFavorite(keep)
                }

                override fun delete(keep: Keep) {
                    UserLibrary.get().deleteFavorite(keep)
                }
            }
    }

    fun check(key: String) {
        submit(key, Function { it.find(key) != null }, false)
    }

    fun toggle(candidate: Keep) {
        val key = candidate.key
        submit(
            key,
            Function { store ->
                val existing = store.find(key)
                if (existing == null) store.save(candidate) else store.delete(existing)
                existing == null
            },
            true,
        )
    }

    fun update(
        key: String,
        name: String,
        picture: String,
    ) {
        synchronized(this) { if (host == null) return }
        val repository = store
        worker.execute {
            val keep = repository.find(key) ?: return@execute
            keep.vodName = name
            keep.vodPic = picture
            repository.save(keep)
        }
    }

    private fun submit(
        key: String,
        operation: Function<Store, Boolean>,
        notify: Boolean,
    ) {
        val request: Request
        synchronized(this) {
            if (host == null) return
            request = Request(this, ++generation, key)
        }
        val repository = store
        val delivery = main
        // A requested write may finish after the screen closes; capture no controller or Host.
        worker.execute {
            val kept = operation.apply(repository)
            delivery.execute { request.deliver(kept, notify) }
        }
    }

    @Synchronized private fun deliver(
        expected: Int,
        key: String,
        kept: Boolean,
        notify: Boolean,
    ) {
        val host = host
        if (host != null && generation == expected && key == host.activeKey()) host.showFavorite(kept, notify)
    }

    @Synchronized override fun close() {
        generation++
        host = null
    }

    private class Request(
        owner: VideoFavoritesController,
        private val generation: Int,
        private val key: String,
    ) {
        private val owner = WeakReference(owner)

        fun deliver(
            kept: Boolean,
            notify: Boolean,
        ) {
            owner.get()?.deliver(generation, key, kept, notify)
        }
    }
}
