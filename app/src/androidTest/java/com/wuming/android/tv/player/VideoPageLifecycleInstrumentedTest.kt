package com.wuming.android.tv.player

import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wuming.android.tv.api.SiteApi
import com.wuming.android.tv.bean.Result
import com.wuming.android.tv.setting.PlayerSettings
import com.wuming.android.tv.ui.activity.VideoActivity
import com.wuming.android.tv.ui.base.PlaybackActivity
import com.wuming.android.tv.ui.base.BaseVideoActivity
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoPageLifecycleInstrumentedTest {
    @Test fun leavingPreviewStopsPlaybackDespiteBackgroundSetting() = verifyLeaving(false, 1)

    @Test fun leavingFullscreenStopsPlaybackDespitePipSetting() = verifyLeaving(true, 2)

    private fun verifyLeaving(fullscreen: Boolean, background: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = File(context.cacheDir, "video-page-lifecycle.mp4")
        instrumentation.context.assets.open("cloud-smoke.mp4").use { input ->
            fixture.outputStream().use { input.copyTo(it) }
        }
        val previous = PlayerSettings.getBackground()
        try {
            PlayerSettings.putBackground(background)
            val uri = fixture.toURI().toString()
            val intent = Intent(context, VideoActivity::class.java)
                .putExtra("key", SiteApi.PUSH).putExtra("id", uri)
                .putExtra("name", "Video lifecycle fixture").putExtra("collect", false)
            ActivityScenario.launch<VideoActivity>(intent).use { scenario ->
                var page: VideoActivity? = null
                var manager: PlayerManager? = null
                val deadline = SystemClock.elapsedRealtime() + 20_000
                var playing = false
                while (!playing && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { activity ->
                        page = activity
                        manager = playerMethod.invoke(activity) as PlayerManager?
                        manager?.setRepeatOne(true)
                        playing = manager?.isPlaying() == true
                    }
                    if (!playing) SystemClock.sleep(100)
                }
                assertTrue("Local fixture must play before leaving", playing)
                scenario.onActivity { activity ->
                    val current = BaseVideoActivity::class.java.getDeclaredMethod("isFullscreen")
                        .apply { isAccessible = true }.invoke(activity) as Boolean
                    if (current != fullscreen) {
                        VideoActivity::class.java.getDeclaredMethod(
                            if (fullscreen) "enterFullscreen" else "exitFullscreen"
                        ).apply { isAccessible = true }.invoke(activity)
                    }
                }
                scenario.moveToState(Lifecycle.State.STARTED)
                instrumentation.runOnMainSync {
                    assertFalse("Losing focus must pause", manager!!.getPlayer()!!.playWhenReady)
                }
                scenario.moveToState(Lifecycle.State.CREATED)
                instrumentation.runOnMainSync {
                    assertFalse(manager!!.isPlaying())
                    assertEquals(Player.STATE_IDLE, manager!!.getPlayer()!!.playbackState)
                    // Simulate both a late catalog result and a completed playlist job.
                    val result = Result.fromJson(org.json.JSONObject()
                        .put("url", uri).put("parse", 0).toString())
                    PlaybackActivity::class.java.getDeclaredMethod("startPlayer", String::class.java,
                        Result::class.java, Boolean::class.javaPrimitiveType, Long::class.javaPrimitiveType,
                        MediaMetadata::class.java).apply { isAccessible = true }
                        .invoke(page, manager!!.getKey(), result, false, 30_000L, MediaMetadata.EMPTY)
                    PlaybackActivity::class.java.getDeclaredMethod("startPlayerDirect", String::class.java,
                        Result::class.java, Long::class.javaPrimitiveType, MediaMetadata::class.java)
                        .apply { isAccessible = true }
                        .invoke(page, manager!!.getKey(), result, 30_000L, MediaMetadata.EMPTY)
                    assertEquals("Late results must not prepare hidden video", Player.STATE_IDLE,
                        manager!!.getPlayer()!!.playbackState)
                }
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.onActivity { activity ->
                    assertFalse("Returning must not restart playback automatically", manager!!.isPlaying())
                    BaseVideoActivity::class.java.getDeclaredMethod("onPlay")
                        .apply { isAccessible = true }.invoke(activity)
                }
                playing = false
                val resumeDeadline = SystemClock.elapsedRealtime() + 10_000
                while (!playing && SystemClock.elapsedRealtime() < resumeDeadline) {
                    scenario.onActivity { playing = manager!!.isPlaying() }
                    if (!playing) SystemClock.sleep(100)
                }
                assertTrue("Play must work after returning", playing)
                scenario.close()
                instrumentation.runOnMainSync {
                    assertFalse("Closing the page must stop audio", manager!!.isPlaying())
                }
            }
        } finally {
            PlayerSettings.putBackground(previous)
            fixture.delete()
        }
    }

    private val playerMethod = PlaybackActivity::class.java.getDeclaredMethod("player").apply {
        isAccessible = true
    }
}
