package com.wuming.android.tv.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wuming.android.tv.R
import com.wuming.android.tv.api.SiteApi
import com.wuming.android.tv.event.ConfigEvent
import com.wuming.android.tv.setting.DisplaySettings
import com.wuming.android.tv.setting.PlayerSettings
import com.wuming.android.tv.ui.activity.SettingActivity
import com.wuming.android.tv.ui.activity.VideoActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LeanbackWallpaperPreviewInstrumentedTest {
    @Test fun defaultWallpaperButtonChangesVisibleBackground() {
        TestActivityLauncher.launch<SettingActivity>(SettingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val original = DisplaySettings.getWall()
                val type = DisplaySettings.getWallType()
                try {
                    DisplaySettings.putWall(1)
                    DisplaySettings.putWallType(0)
                    ConfigEvent.wall()
                    val wall = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                    fun pixels(): IntArray {
                        val bitmap = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        canvas.scale(64f / wall.width, 36f / wall.height)
                        wall.draw(canvas)
                        val result = IntArray(64 * 36)
                        bitmap.getPixels(result, 0, 64, 0, 0, 64, 36)
                        bitmap.recycle()
                        return result
                    }
                    assertTrue(wall.width > 0 && wall.height > 0)
                    val before = pixels()
                    assertTrue(activity.findViewById<View>(R.id.wallDefault).performClick())
                    assertEquals(2, DisplaySettings.getWall())
                    assertFalse("Opaque tint hid the wallpaper change", before.contentEquals(pixels()))
                } finally {
                    DisplaySettings.putWall(original)
                    DisplaySettings.putWallType(type)
                    ConfigEvent.wall()
                }
            }
        }
    }

    @Test fun previewHidesInformationAndClockAfterFullscreenAndRefresh() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, VideoActivity::class.java)
            .putExtra("key", SiteApi.PUSH).putExtra("id", "http://127.0.0.1:1/preview-test.mp4")
            .putExtra("name", "Preview overlay regression")
        TestActivityLauncher.launch<VideoActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val mode = PlayerSettings.getVideoInfo()
                fun invoke(name: String) {
                    VideoActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
                }
                try {
                    PlayerSettings.putVideoInfo(0)
                    invoke("enterFullscreen")
                    invoke("exitFullscreen")
                    fun assertHidden() {
                        assertEquals(View.GONE, activity.findViewById<View>(R.id.videoInfo).visibility)
                        assertEquals(View.GONE, activity.findViewById<View>(R.id.videoClock).visibility)
                    }
                    assertHidden()
                    invoke("updateVideoInfo")
                    assertHidden()
                    invoke("hideInfo")
                    assertHidden()
                } finally { PlayerSettings.putVideoInfo(mode) }
            }
        }
    }
}
