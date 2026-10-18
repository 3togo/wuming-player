package com.wuming.android.tv.ui.base

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.wuming.android.tv.App
import com.wuming.android.tv.utils.AppLog
import com.wuming.android.tv.utils.PlatformCapabilities
import java.lang.ref.WeakReference
import java.util.concurrent.Executor
import java.util.function.Consumer

/** A screen-owned subscription; stopped sessions cannot enqueue or deliver UI updates. */
@JvmSuppressWildcards
class PlaybackNetworkObserver(
    private val source: Source,
    private val main: Executor,
    private val remove: Consumer<Runnable>,
    update: Runnable,
) : AutoCloseable {
    fun interface Source {
        fun subscribe(changed: Runnable): Runnable?
    }

    private var update: Runnable? = update
    private var unsubscribe: Runnable? = null
    private var session: Session? = null
    private var closed = false
    constructor(
        context: Context,
        update: Runnable,
    ) : this(androidSource(context.applicationContext), App.mainThreadExecutor, Consumer { App.removeCallbacks(it) }, update)

    @Synchronized fun start() {
        if (closed || session != null) return
        val current = Session(this)
        session = current
        unsubscribe = source.subscribe(current::changed)
        if (unsubscribe == null) {
            remove.accept(session!!)
            session = null
        }
    }

    @Synchronized fun stop() {
        val previous = session
        session = null
        val cleanup = unsubscribe
        unsubscribe = null
        if (previous != null) remove.accept(previous)
        cleanup?.run()
    }

    @Synchronized private fun schedule(requested: Session) {
        if (session !== requested || closed) return
        remove.accept(requested)
        main.execute(requested)
    }

    @Synchronized private fun deliver(requested: Session) {
        if (session === requested && !closed) update!!.run()
    }

    @Synchronized override fun close() {
        closed = true
        stop()
        update = null
    }

    private class Session(
        owner: PlaybackNetworkObserver,
    ) : Runnable {
        private val owner = WeakReference(owner)

        fun changed() {
            owner.get()?.schedule(this)
        }

        override fun run() {
            owner.get()?.deliver(this)
        }
    }

    companion object {
        private fun androidSource(context: Context): Source =
            Source { changed ->
                val callback =
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            changed.run()
                        }

                        override fun onLost(network: Network) {
                            changed.run()
                        }

                        override fun onCapabilitiesChanged(
                            network: Network,
                            capabilities: NetworkCapabilities,
                        ) {
                            changed.run()
                        }
                    }
                try {
                    if (PlatformCapabilities.registerDefaultNetworkCallback(context, callback)) {
                        Runnable {
                            PlatformCapabilities.unregisterNetworkCallback(context, callback)
                        }
                    } else {
                        null
                    }
                } catch (error: RuntimeException) {
                    AppLog.e("PlaybackNetworkObserver", "Network observation unavailable", error)
                    null
                }
            }
    }
}
