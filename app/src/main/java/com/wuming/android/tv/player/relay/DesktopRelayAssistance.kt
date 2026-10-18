package com.wuming.android.tv.player.relay

import android.os.SystemClock
import androidx.media3.common.Player
import com.wuming.android.tv.player.engine.PlaySpec
import com.wuming.android.tv.utils.Task
import com.wuming.android.tv.utils.UiTaskScope
import com.wuming.tv.core.playback.relay.RelayAssistancePolicy
import com.wuming.tv.core.playback.relay.RelayOriginal
import java.lang.ref.WeakReference
import java.util.concurrent.FutureTask

/** Playback-owned Android port. Preparation never blocks the player thread or replaces healthy playback. */
class DesktopRelayAssistance(private val host: Host) : AutoCloseable {
    interface Host {
        fun spec(): PlaySpec?
        fun player(): Player?
        fun restart()
    }
    private val policy = RelayAssistancePolicy()
    private val tasks = UiTaskScope()
    private class Tick(owner: DesktopRelayAssistance) : Runnable {
        private val owner = WeakReference(owner)
        override fun run() { owner.get()?.sample() }
    }
    private val tick = Tick(this)
    private var job: Preparation? = null
    private var activeRelay: String? = null
    private var closed = false

    fun reset() { policy.reset() }

    fun isPreparing(): Boolean = job != null

    fun prepare(spec: PlaySpec) {
        cancelPreparation()
        tasks.remove(tick)
        if (activeRelay != spec.getUrl()) activeRelay?.let(DesktopRelayClient::release)
        activeRelay = spec.getUrl()?.takeIf { DesktopRelayClient.original(it) != null }
    }

    fun stateChanged() {
        if (closed) return
        tasks.remove(tick)
        val player = host.player()
        val buffering = player?.playbackState == Player.STATE_BUFFERING
        val playing = player?.playWhenReady == true
        if (!buffering || !playing) cancelPreparation()
        if (!buffering || !playing) policy.sample(SystemClock.elapsedRealtime(), buffering, playing, activeRelay != null)
        else if (host.spec()?.allowsDesktopAssistance() == true) sample()
    }

    private fun sample() {
        if (closed) return
        val spec = host.spec() ?: return
        val player = host.player() ?: return
        val now = SystemClock.elapsedRealtime()
        if (job?.expired(now) == true) cancelPreparation()
        val buffering = player.playbackState == Player.STATE_BUFFERING
        val playing = player.playWhenReady
        val action = policy.sample(now, buffering, playing, activeRelay != null)
        if (!buffering || !playing) { cancelPreparation(); return }
        when (action) {
            RelayAssistancePolicy.Action.REQUEST_DESKTOP -> {
                if (spec.allowsDesktopAssistance() && spec.getDrm() == null && DesktopRelayClient.available() &&
                    com.wuming.android.tv.utils.VideoNetworkPolicy.blockedMessage() == 0) {
                    // Live keys preserve the remote URL even when Android purifies its playlist locally.
                    val url = spec.getKey()?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
                        ?: spec.getUrl() ?: return
                    val original = RelayOriginal(url, spec.getHeaders().toMap(), spec.getFormat())
                    job = Preparation(this, spec, original).also { Task.largeExecutor().execute(it.future) }
                }
            }
            RelayAssistancePolicy.Action.FALLBACK_DIRECT -> {
                if (DesktopRelayClient.fallback(spec)) { host.restart(); return }
            }
            RelayAssistancePolicy.Action.NONE -> Unit
        }
        tasks.post(tick, 1000L)
    }

    private fun ready(preparation: Preparation, spec: PlaySpec, route: String?) {
        if (job !== preparation || host.spec() !== spec || closed || preparation.expired(SystemClock.elapsedRealtime())) {
            route?.let(DesktopRelayClient::release)
            return
        }
        job = null
        if (route == null) return
        val player = host.player()
        if (!DesktopRelayClient.enabled || player?.playbackState != Player.STATE_BUFFERING || !player.playWhenReady) {
            DesktopRelayClient.release(route)
            return
        }
        spec.setUrl(route)
        spec.setHeaders(emptyMap()) // Upstream credentials stay on the desktop, never on LAN media requests.
        spec.setFormat("application/x-mpegURL")
        activeRelay = route
        host.restart()
    }

    private fun cancelPreparation() { job?.close(); job = null }

    fun stop() {
        cancelPreparation()
        tasks.remove(tick)
        activeRelay?.let { route ->
            val original = DesktopRelayClient.original(route)
            val spec = host.spec()
            if (original != null && spec?.getUrl() == route) {
                spec.setUrl(original.url)
                spec.setHeaders(original.headers)
                spec.setFormat(original.format)
            }
            DesktopRelayClient.release(route)
        }
        activeRelay = null
    }

    override fun close() { closed = true; stop(); tasks.close() }

    private class Preparation(owner: DesktopRelayAssistance, private val spec: PlaySpec, private val original: RelayOriginal) : AutoCloseable {
        private val owner = WeakReference(owner)
        private val request = DesktopRelayClient.RelayRequest()
        private val delivery = UiTaskScope()
        private var closed = false
        private var route: String? = null
        private val startedAt = SystemClock.elapsedRealtime()
        fun expired(now: Long): Boolean = now - startedAt >= RelayAssistancePolicy.REQUEST_BUDGET_MILLIS
        val future = FutureTask<Void>({
            val result = DesktopRelayClient.request(original, request)
            synchronized(this) {
                if (closed) result?.let(DesktopRelayClient::release)
                else {
                    route = result
                    delivery.post(Runnable {
                        val target = this.owner.get()
                        val value = synchronized(this) { route.also { route = null } }
                        if (target == null) value?.let(DesktopRelayClient::release)
                        else target.ready(this, spec, value)
                        close()
                    }, 0L)
                }
            }
            null
        })

        @Synchronized override fun close() {
            closed = true
            request.close()
            future.cancel(true)
            delivery.close()
            route?.let(DesktopRelayClient::release)
            route = null
            owner.clear()
        }
    }
}
