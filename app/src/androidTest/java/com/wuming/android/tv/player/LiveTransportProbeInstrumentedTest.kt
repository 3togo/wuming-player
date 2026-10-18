package com.wuming.android.tv.player

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.catvod.net.OkHttp
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import com.github.catvod.net.OkDns
import com.github.catvod.bean.Doh
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Opt-in, bounded real-device transport comparison; never logs signed URLs or headers. */
@RunWith(AndroidJUnit4::class)
class LiveTransportProbeInstrumentedTest {
    @Test fun decodePrivateCandidate() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val filename = arguments.getString("live_probe_file")
        val id = arguments.getString("live_candidate_id")
        val observeMillis = arguments.getString("live_observe_ms")?.toLong()?.coerceIn(30000, 90000) ?: 30000L
        require(filename != null && filename.matches(Regex("[a-zA-Z0-9_-]+\\.json")) && id != null)
        val rows = JSONArray(instrumentation.targetContext.filesDir.resolve(filename).readText())
        val row = (0 until rows.length()).map(rows::getJSONObject).first { it.getString("id") == id }
        var playbackUrl = row.getString("url")
        val relayCode = row.optString("relay_pair_code")
        if (relayCode.isNotEmpty()) {
            val relayClient = com.wuming.android.tv.player.relay.DesktopRelayClient
            val relay = relayClient.discover().first { it.service.id == row.getString("relay_id") }
            relayClient.pair(relay, relayCode)
            val original = com.wuming.android.tv.bean.Result().apply { setUrl(playbackUrl) }
            val routed = relayClient.route(original)
            check(routed.realUrl != playbackUrl && routed.realUrl.startsWith(relay.address)) { "Discovery/pairing did not route live playback" }
            playbackUrl = routed.realUrl
            val spec = com.wuming.android.tv.player.engine.PlaySpec.from(routed, "relay-check", null)
            check(relayClient.fallback(spec) && spec.getUrl() == original.realUrl) { "Direct fallback did not restore original URL" }
            check(!relayClient.fallback(spec)) { "Fallback must be one-use" }
            relayClient.setEnabled(true)
            playbackUrl = relayClient.route(original).realUrl
            check(playbackUrl != original.realUrl) { "Relay did not resume after fallback reset" }
        }
        // Compare the strongest measured route in the isolated testing process only.
        val originalClient = OkHttp.player()
        val playerField = OkHttp::class.java.getDeclaredField("player").apply { isAccessible = true }
        if (row.optString("protocol") == "h1") playerField.set(OkHttp.get(),
            originalClient.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build())
        OkHttp.dns().setDoh(Doh())
        val intent = android.content.Intent(instrumentation.targetContext,
            com.wuming.android.tv.ui.activity.VideoActivity::class.java)
            .putExtra("key", com.wuming.android.tv.api.SiteApi.PUSH)
            .putExtra("id", playbackUrl).putExtra("name", "TVB Plus private candidate")
            .putExtra("collect", false)
        val accessor = com.wuming.android.tv.ui.base.PlaybackActivity::class.java
            .getDeclaredMethod("player").apply { isAccessible = true }
        val probe = PlaybackSmokeProbe()
        var outcome = "failed"
        try {
            androidx.test.core.app.ActivityScenario.launch<com.wuming.android.tv.ui.activity.VideoActivity>(intent).use { scenario ->
                val deadline = SystemClock.elapsedRealtime() + 60000
                while (SystemClock.elapsedRealtime() < deadline) {
                    LiveChannelSmokeInstrumentedTest.sample(scenario, accessor, probe)
                    probe.save("starting")
                    check(!probe.uiError && probe.errorCode == null) { "Playback error" }
                    if (probe.frames >= 30 && probe.playing) break
                    SystemClock.sleep(250)
                }
                check(probe.frames >= 30 && probe.playing) { "No decoded video within 60 seconds" }
                if (relayCode.isNotEmpty()) {
                    scenario.onActivity { activity ->
                        val manager = accessor.invoke(activity) as PlayerManager
                        val listener = PlayerManager::class.java.getDeclaredField("listener").apply { isAccessible = true }
                            .get(manager) as androidx.media3.common.Player.Listener
                        listener.onPlayerError(androidx.media3.common.PlaybackException("Live window moved", null,
                            androidx.media3.common.PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW))
                        check(manager.getUrl() == playbackUrl) { "Recoverable live-window event discarded the relay route" }
                    }
                }
                val progress = probe.diagnostics.progressMillis
                val frames = probe.frames
                val started = SystemClock.elapsedRealtime()
                while (SystemClock.elapsedRealtime() - started < observeMillis) {
                    SystemClock.sleep(1000)
                    LiveChannelSmokeInstrumentedTest.sample(scenario, accessor, probe)
                    probe.save("observing")
                    check(!probe.uiError && probe.errorCode == null) { "Playback error" }
                }
                check(probe.diagnostics.progressMillis - progress >= observeMillis * 3 / 5 && probe.frames - frames >= 60) {
                    "Playback did not continue advancing"
                }
                check(probe.playing && probe.diagnostics.maxStallMillis < 8000) { "Playback stalled" }
                outcome = "passed"
            }
        } finally {
            playerField.set(OkHttp.get(), originalClient)
            probe.save(outcome)
            if (relayCode.isNotEmpty()) com.wuming.android.tv.player.relay.DesktopRelayClient.forget()
        }
    }

    @Test fun comparePrivateLiveCandidates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val filename = InstrumentationRegistry.getArguments().getString("live_probe_file")
        require(filename != null && filename.matches(Regex("[a-zA-Z0-9_-]+\\.json")))
        val candidates = JSONArray(context.filesDir.resolve(filename).readText())
        require(candidates.length() in 1..20)
        val results = JSONArray()
        for (index in 0 until candidates.length()) {
            val candidate = candidates.getJSONObject(index)
            val resolver = OkDns()
            if (candidate.optString("dns") == "tencent")
                resolver.setDoh(Doh().url("https://doh.pub/dns-query"))
            val result = JSONObject().put("id", candidate.getString("id"))
                .put("dns", candidate.optString("dns", "system"))
            val client = OkHttp.player().newBuilder().dns(resolver)
                .connectionPool(okhttp3.ConnectionPool()).dispatcher(okhttp3.Dispatcher())
                .protocols(if (candidate.optString("protocol") == "h1") listOf(Protocol.HTTP_1_1)
                    else listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
                .callTimeout(18, TimeUnit.SECONDS).build()
            var bytes = 0L
            var mediaStarted = 0L
            val started = SystemClock.elapsedRealtime()
            try {
                var current = candidate.getString("url")
                for (depth in 0 until 4) {
                    val playlist = text(client, current)
                    check(playlist.second.trimStart().startsWith("#EXTM3U")) { "Not HLS" }
                    val lines = playlist.second.lineSequence().map(String::trim).toList()
                    lines.firstOrNull { it.startsWith("#EXT-X-STREAM-INF:") }
                        ?.let { result.put("variant", it) }
                    val references = lines.filter { it.isNotEmpty() && !it.startsWith('#') }
                    check(references.isNotEmpty()) { "No references" }
                    val media = playlist.second.contains("#EXTINF:")
                    // Avoid the newest segment: some relays advertise it before upload completes.
                    current = playlist.first.resolve(if (media) references[(references.size - 3).coerceAtLeast(0)]
                        else references.first())!!.toString()
                    if (media) {
                        mediaStarted = SystemClock.elapsedRealtime()
                        client.newCall(Request.Builder().url(current).build()).execute().use { response ->
                            result.put("status", response.code).put("protocol", response.protocol.toString())
                                .put("mediaHost", response.request.url.host)
                                .put("length", response.body?.contentLength() ?: -1)
                            check(response.isSuccessful) { "Media HTTP ${response.code}" }
                            val input = response.body!!.byteStream()
                            val buffer = ByteArray(16384)
                            while (bytes < 8388608 && SystemClock.elapsedRealtime() - mediaStarted < 15000) {
                                val count = input.read(buffer)
                                if (count < 0) { result.put("complete", true); break }
                                bytes += count
                            }
                        }
                        break
                    }
                }
            } catch (error: Exception) {
                result.put("error", error.javaClass.simpleName)
            } finally {
                val elapsed = if (mediaStarted > 0) SystemClock.elapsedRealtime() - mediaStarted else 0
                result.put("bytes", bytes).put("mediaMs", elapsed)
                    .put("bps", if (elapsed > 0) bytes * 1000 / elapsed else 0)
                    .put("totalMs", SystemClock.elapsedRealtime() - started)
                results.put(result)
                context.filesDir.resolve("live-probe-result.json").writeText(results.toString(2))
                Log.i("LiveTransportProbe", result.toString())
                client.dispatcher.cancelAll()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }

    @Test fun compareLiveSegmentTransports() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val name = InstrumentationRegistry.getArguments().getString("live_channel")
        assertNotNull("Explicit live_channel required", name)
        var selected = false
        val url = instrumentation.targetContext.assets.open("live/public_samples.m3u8")
            .bufferedReader().useLines { lines ->
                lines.firstOrNull { line ->
                    if (line.startsWith("#EXTINF:")) selected = line.substringAfterLast(',') == name
                    selected && line.startsWith("http")
                }
            }
        assertNotNull("Exact bundled channel not found", url)
        // Alternate order to reduce a transient route change being mistaken for a protocol effect.
        for (mode in listOf("h2", "h1", "h1", "h2")) {
            val client = OkHttp.player().newBuilder()
                .connectionPool(okhttp3.ConnectionPool())
                .dispatcher(okhttp3.Dispatcher())
                .protocols(if (mode == "h1") listOf(Protocol.HTTP_1_1)
                    else listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .readTimeout(8, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
            var bytes = 0L
            var protocol = "unknown"
            val started = SystemClock.elapsedRealtime()
            try {
                var current = url!!
                for (depth in 0 until 3) {
                    val playlist = text(client, current)
                    check(playlist.second.trimStart().startsWith("#EXTM3U")) { "Not an HLS playlist" }
                    val references = playlist.second.lineSequence()
                        .map(String::trim).filter { it.isNotEmpty() && !it.startsWith('#') }.toList()
                    check(references.isNotEmpty()) { "No playlist references" }
                    val media = playlist.second.contains("#EXTINF:")
                    current = playlist.first.resolve(if (media) references.last() else references.first())!!.toString()
                    if (media) {
                        val mediaStarted = SystemClock.elapsedRealtime()
                        client.newCall(Request.Builder().url(current).build()).execute().use { response ->
                            check(response.isSuccessful) { "Media HTTP ${response.code}" }
                            protocol = response.protocol.toString()
                            val input = response.body!!.byteStream()
                            val buffer = ByteArray(16384)
                            while (bytes < 262144 && SystemClock.elapsedRealtime() - mediaStarted < 12000) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                bytes += count
                            }
                        }
                        val elapsed = SystemClock.elapsedRealtime() - mediaStarted
                        Log.i("LiveTransportProbe", "mode=$mode,protocol=$protocol,bytes=$bytes,mediaMs=$elapsed,bps=${bytes * 1000 / elapsed.coerceAtLeast(1)}")
                        break
                    }
                }
            } catch (error: Exception) {
                Log.i("LiveTransportProbe", "mode=$mode,protocol=$protocol,bytes=$bytes,totalMs=${SystemClock.elapsedRealtime() - started},error=${error.javaClass.simpleName}")
            } finally {
                client.dispatcher.cancelAll()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }

    private fun text(client: OkHttpClient, url: String): Pair<okhttp3.HttpUrl, String> =
        client.newCall(Request.Builder().url(url).build()).execute().use {
            check(it.isSuccessful) { "Playlist HTTP ${it.code}" }
            it.request.url to it.body!!.string()
        }
}
