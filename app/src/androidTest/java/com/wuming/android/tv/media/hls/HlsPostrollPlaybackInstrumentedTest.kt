package com.wuming.android.tv.media.hls

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wuming.android.tv.api.SiteApi
import com.wuming.android.tv.player.PlayerManager
import com.wuming.android.tv.setting.AppSettings
import com.wuming.android.tv.ui.activity.VideoActivity
import com.wuming.android.tv.ui.base.PlaybackActivity
import java.io.File
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit live-device check. Uses the real VideoActivity preparation/player,
 * not a test player or prefiltered fixture. Host supplies the original manifest
 * and URL; no source URL, media frames or account credentials are bundled. */
@RunWith(AndroidJUnit4::class)
class HlsPostrollPlaybackInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val output get() = File(context.cacheDir, "postroll-validation").apply { mkdirs() }

    private fun manager(activity: VideoActivity): PlayerManager? =
        PlaybackActivity::class.java.getDeclaredMethod("player").apply { isAccessible = true }
            .invoke(activity) as? PlayerManager

    private fun screenshot(name: String) {
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }

    private data class Sample(val position: Long, val duration: Long, val state: Int,
                              val width: Int, val height: Int, val playing: Boolean,
                              val error: String?, val url: String)

    private fun sample(scenario: ActivityScenario<VideoActivity>): Sample {
        var value = Sample(0, 0, Player.STATE_IDLE, 0, 0, false, null, "")
        scenario.onActivity { activity -> manager(activity)?.let { player ->
            value = Sample(player.getPosition(), player.getDuration(), player.getPlaybackState(),
                player.getVideoWidth(), player.getVideoHeight(), player.isPlaying(),
                player.getPlayer()?.playerError?.errorCodeName, player.getUrl().orEmpty())
        } }
        return value
    }

    @Test fun realEpisodeRetainsEveryCreditSegmentAndEndsBeforeTheAppendedAd() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit live-provider playback opt-in", args.getString("postrollLive") == "true")
        assertTrue("Enable the app's HLS purification setting before this live check", AppSettings.isPurify())
        val url = checkNotNull(args.getString("postrollUrl"))
        val original = File(context.filesDir, "postroll-fixture/original.m3u8").readText()
        fun segments(text: String) = text.lineSequence().filter { it.isNotBlank() && !it.startsWith('#') }.toList()
        val allSegments = segments(original)
        assertEquals(717, allSegments.size)
        val intent = Intent(context, VideoActivity::class.java)
            .putExtra("collect", false).putExtra("key", SiteApi.PUSH).putExtra("id", url)
            .putExtra("name", "醉梦 第02集 — appended-ad validation")
        val samples = JSONArray()
        val ended = AtomicBoolean(false)
        val report = JSONObject().put("package", context.packageName).put("passed", false)
        try {
            ActivityScenario.launch<VideoActivity>(intent).use { scenario ->
                var ready = sample(scenario)
                val startupDeadline = SystemClock.elapsedRealtime() + 90_000
                while (SystemClock.elapsedRealtime() < startupDeadline) {
                    ready = sample(scenario)
                    assertNull("Playback error", ready.error)
                    if (ready.state == Player.STATE_READY && ready.width > 0 && ready.height > 0) break
                    SystemClock.sleep(250)
                }
                assertEquals("Player never became ready", Player.STATE_READY, ready.state)
                assertEquals("Filtered duration", 2_853_680.0, ready.duration.toDouble(), 40.0)
                assertTrue("Production purification URL", URL(ready.url).path.startsWith("/m3u8"))
                val filtered = URL(ready.url).openConnection().apply { connectTimeout = 5000; readTimeout = 5000 }
                    .getInputStream().bufferedReader().use { it.readText() }
                File(output, "filtered.m3u8").writeText(filtered)
                assertEquals("Every programme/credit URI must survive in order", allSegments.take(713), segments(filtered))
                assertTrue("Filtered VOD must retain ENDLIST", filtered.trimEnd().endsWith("#EXT-X-ENDLIST"))
                scenario.onActivity { activity ->
                    val player = checkNotNull(manager(activity))
                    player.getPlayer()!!.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_ENDED) ended.set(true)
                        }
                    })
                    player.seekTo(2_803_680)
                    player.play()
                }
                var creditsCaptured = false
                var finalCaptured = false
                var maximumPosition = 0L
                val deadline = SystemClock.elapsedRealtime() + 120_000
                while (SystemClock.elapsedRealtime() < deadline && !ended.get()) {
                    val current = sample(scenario)
                    assertNull("Ending playback error", current.error)
                    assertEquals("Ending must retain the filtered duration", 2_853_680.0, current.duration.toDouble(), 40.0)
                    maximumPosition = maxOf(maximumPosition, current.position)
                    samples.put(JSONObject().put("positionMs", current.position).put("state", current.state)
                        .put("playing", current.playing).put("width", current.width).put("height", current.height))
                    if (!creditsCaptured && current.playing && current.position in 2_803_680..2_820_000) {
                        screenshot("credits"); creditsCaptured = true
                    }
                    if (!finalCaptured && current.playing && current.position >= 2_848_000) {
                        screenshot("final-credit-card"); finalCaptured = true
                    }
                    SystemClock.sleep(250)
                }
                assertTrue("Credits must actually render", creditsCaptured)
                assertTrue("Final programme card must render", finalCaptured)
                assertTrue("Natural end-of-stream must be observed", ended.get())
                assertTrue("Ending must advance through the final segment", maximumPosition >= 2_852_000)
                report.put("passed", true).put("originalSegments", 717).put("retainedSegments", 713)
                    .put("removedSegments", 4).put("filteredDurationMs", ready.duration)
                    .put("naturalEof", true).put("maximumPositionMs", maximumPosition)
            }
        } finally {
            report.put("samples", samples)
            File(output, "result.json").writeText(report.toString(2))
        }
    }
}
