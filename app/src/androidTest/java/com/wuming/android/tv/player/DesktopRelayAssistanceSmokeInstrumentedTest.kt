package com.wuming.android.tv.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wuming.android.tv.player.engine.ExoPlayerEngine
import com.wuming.android.tv.player.engine.PlaySpec
import com.wuming.android.tv.player.engine.PlayerEngine
import com.wuming.android.tv.player.relay.DesktopRelayClient
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Explicit opt-in real LAN smoke. Only the Android direct fetch is deliberately stalled. */
@RunWith(AndroidJUnit4::class)
class DesktopRelayAssistanceSmokeInstrumentedTest {
    @Test fun automaticStallRequestsDesktopDecodesAndFallsBack() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val filename = InstrumentationRegistry.getArguments().getString("relay_smoke_file")
        require(filename != null && filename.matches(Regex("[a-zA-Z0-9_-]+\\.json")))
        val context = instrumentation.targetContext
        val fixture = JSONObject(context.filesDir.resolve(filename).readText())
        val source = fixture.getString("url")
        val prefs = context.getSharedPreferences("desktop_relay", Context.MODE_PRIVATE)
        val saved = prefs.all.toMap()
        val blocked = AtomicBoolean(true)
        val directRequests = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().url.toString() == source && blocked.get()) {
                directRequests.incrementAndGet()
                val deadline = SystemClock.elapsedRealtime() + 60_000
                while (blocked.get() && !chain.call().isCanceled() && SystemClock.elapsedRealtime() < deadline) {
                    Thread.sleep(50)
                }
                if (chain.call().isCanceled() || blocked.get()) throw IOException("Injected direct-fetch stall")
            }
            chain.proceed(chain.request())
        }.build()
        var manager: PlayerManager? = null
        val probe = PlaybackSmokeProbe()
        val report = JSONObject()
        var result = "failed"
        try {
            DesktopRelayClient.forget()
            val relay = DesktopRelayClient.discover().first { it.service.id == fixture.getString("relay_id") }
            DesktopRelayClient.pair(relay, fixture.getString("relay_pair_code"))
            val intent = Intent().setClassName(context, "com.wuming.android.tv.ui.activity.MemoryTestActivity")
            ActivityScenario.launch<Activity>(intent).use { scenario ->
                val started = SystemClock.elapsedRealtime()
                scenario.onActivity { activity ->
                    val player = ExoPlayer.Builder(activity).setMediaSourceFactory(
                        DefaultMediaSourceFactory(activity).setDataSourceFactory(OkHttpDataSource.Factory(client))).build()
                    val view = PlayerView(activity).apply { this.player = player }
                    activity.setContentView(view)
                    val constructor = ExoPlayerEngine::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType, Player::class.java)
                        .apply { isAccessible = true }
                    manager = PlayerManager(null, constructor.newInstance(PlayerEngine.HARD, player))
                    manager!!.start(PlaySpec.from(source, source, emptyMap(), null)
                        .withDesktopAssistance(true).apply { setFormat("application/x-mpegURL") }, 60_000)
                    check(manager!!.getUrl() == source) { "Playback must begin directly" }
                }
                fun url(): String? {
                    var value: String? = null
                    scenario.onActivity { value = manager!!.getUrl(); probe.sample(manager!!.getPlayer() as ExoPlayer) }
                    return value
                }
                while (SystemClock.elapsedRealtime() - started < 7000) {
                    check(url() == source) { "Desktop requested before sustained buffering" }
                    SystemClock.sleep(250)
                }
                check(directRequests.get() > 0) { "Injected direct request did not start" }
                val deadline = started + 25_000
                var route = url()
                while (route == source && SystemClock.elapsedRealtime() < deadline) {
                    SystemClock.sleep(250); route = url()
                }
                check(route != null && route != source && route!!.startsWith(relay.address)) { "Automatic desktop handoff did not occur" }
                val handoffMillis = SystemClock.elapsedRealtime() - started
                check(handoffMillis >= 8000) { "Handoff ignored the buffering threshold" }
                report.put("handoffMs", handoffMillis).put("directStallRequests", directRequests.get())
                val decodeDeadline = SystemClock.elapsedRealtime() + 60_000
                while ((!probe.playing || probe.frames < 30) && SystemClock.elapsedRealtime() < decodeDeadline) {
                    url(); probe.save("starting"); check(probe.errorCode == null) { "Relay decoder error" }; SystemClock.sleep(250)
                }
                check(probe.playing && probe.frames >= 30) { "Desktop route did not decode video" }
                val initialFrames = probe.frames
                val initialProgress = probe.diagnostics.progressMillis
                val observation = SystemClock.elapsedRealtime()
                while (SystemClock.elapsedRealtime() - observation < 30_000) {
                    SystemClock.sleep(1000)
                    check(url() == route) { "Healthy desktop playback lost its route" }
                    check(probe.errorCode == null) { "Relay playback error" }
                    probe.save("observing")
                }
                check(probe.frames - initialFrames >= 60 && probe.diagnostics.progressMillis - initialProgress >= 18_000) {
                    "Desktop playback failed to advance"
                }
                report.put("relayFrames", probe.frames - initialFrames)
                    .put("relayProgressMs", probe.diagnostics.progressMillis - initialProgress)
                    .put("width", probe.width).put("height", probe.height)
                blocked.set(false)
                scenario.onActivity {
                    val listener = PlayerManager::class.java.getDeclaredField("listener").apply { isAccessible = true }
                        .get(manager) as Player.Listener
                    listener.onPlayerError(PlaybackException("Injected terminal error", null,
                        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
                    check(manager!!.getUrl() == source) { "Terminal failure did not restore direct playback" }
                }
                check(!DesktopRelayClient.fallback(PlaySpec.from("released", route, emptyMap(), null))) {
                    "Fallback retained a reusable relay record"
                }
                var released = false
                val releaseDeadline = SystemClock.elapsedRealtime() + 12_000
                while (!released && SystemClock.elapsedRealtime() < releaseDeadline) {
                    client.newCall(Request.Builder().url(route!!).build()).execute().use { released = it.code == 401 }
                    if (!released) SystemClock.sleep(250)
                }
                check(released) { "Desktop session was not released" }
                report.put("terminalFallback", true).put("sessionReleased", true)
                result = "passed"
            }
        } finally {
            blocked.set(false)
            instrumentation.runOnMainSync { manager?.release() }
            DesktopRelayClient.forget()
            prefs.edit().clear().apply {
                saved.forEach { (key, value) -> when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                } }
            }.commit()
            client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow()
            report.put("result", result)
            context.cacheDir.resolve("relay-assistance-smoke.json").writeText(report.toString(2))
            probe.save(result)
        }
    }
}
