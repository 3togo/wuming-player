package com.wuming.android.tv.utils

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.Html
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.wuming.android.tv.App
import com.wuming.android.tv.BuildConfig
import com.wuming.android.tv.R
import com.wuming.android.tv.bean.BeanUtils
import com.github.catvod.utils.Shell
import java.net.NetworkInterface
import java.util.Formatter
import java.util.Locale
import java.util.regex.Pattern

object Util {
    private const val TAG = "Util"


    @JvmStatic
    fun toggleFullscreen(activity: Activity, fullscreen: Boolean) {
        if (fullscreen) hideSystemUI(activity)
        else showSystemUI(activity)
    }

    @JvmStatic
    fun hideSystemUI(activity: Activity) {
        hideSystemUI(activity.window)
    }

    @JvmStatic
    fun hideSystemUI(window: Window) {
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        insets.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE)
        insets.hide(WindowInsetsCompat.Type.systemBars())
    }

    @JvmStatic
    fun showSystemUI(activity: Activity) {
        showSystemUI(activity.window)
    }

    @JvmStatic
    fun showSystemUI(window: Window) {
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    @JvmStatic
    fun showKeyboard(view: View) {
        if (!view.requestFocus()) return
        val imm = App.get().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        view.postDelayed({
            if (!view.isAttachedToWindow) return@postDelayed
            try {
                imm.showSoftInput(view, 0)
            } catch (ignored: RuntimeException) {
            }
        }, 250)
    }

    @JvmStatic
    fun hideKeyboard(view: View) {
        val imm = App.get().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        val windowToken: IBinder? = view.windowToken
        if (windowToken == null) return
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    @Suppress("DEPRECATION")
    @JvmStatic
    fun getBrightness(activity: Activity): Float {
        return try {
            val value = activity.window.attributes.screenBrightness
            if (WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL >= value &&
                value >= WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
            ) value
            else Settings.System.getFloat(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255
        } catch (e: Exception) {
            0.5f
        }
    }

    @JvmStatic
    fun getClipText(): CharSequence {
        val manager = App.get().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clipData = manager?.primaryClip
        if (clipData == null || clipData.itemCount == 0) return ""
        return clipData.getItemAt(0).text
    }

    @JvmStatic
    fun isActive(activity: Activity?): Boolean = activity != null && !activity.isFinishing && !activity.isDestroyed

    @JvmStatic
    fun copy(text: String) {
        if (copySilent(text)) Notify.show(R.string.copied)
    }

    @JvmStatic
    fun copySilent(text: String): Boolean {
        return try {
            val manager = App.get().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("", text))
            true
        } catch (e: Exception) {
            AppLog.e(TAG, e)
            false
        }
    }

    @JvmStatic
    fun getNumber(text: String): Int = com.wuming.tv.core.catalog.EpisodeMatchPolicy.number(text)

    @JvmStatic
    fun clean(text: String): String {
        if (!text.contains("<")) return text
        val sb = StringBuilder()
        val decoded = Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString()
            .replace("\u00A0", " ")
            .replace("\u3000", " ")
        for (line in decoded.split(Regex("\\r?\\n"))) sb.append(line.trim()).append("\n")
        return sb.toString().trim()
    }

    @JvmStatic
    fun getAndroidId(): String {
        return try {
            val id = Settings.Secure.getString(App.get().contentResolver, Settings.Secure.ANDROID_ID)
            if (BeanUtils.isEmpty(id)) throw NullPointerException()
            id
        } catch (e: Exception) {
            "0000000000000000"
        }
    }

    @JvmStatic
    fun getSerial(): String = Shell.exec("getprop ro.serialno").replace("\n", "")

    @JvmStatic
    fun getMac(name: String): String {
        return try {
            val nif = NetworkInterface.getByName(name)
            if (nif == null || nif.hardwareAddress == null) return ""
            Hex.encodeUpperColon(nif.hardwareAddress)
        } catch (e: Exception) {
            ""
        }
    }

    @JvmStatic
    fun getDeviceName(): String {
        val model = Build.MODEL
        val manufacturer = Build.MANUFACTURER
        return if (model.startsWith(manufacturer)) model else "$manufacturer $model"
    }

    @JvmStatic
    fun isLeanback(): Boolean = "leanback" == BuildConfig.FLAVOR_mode

    @JvmStatic
    fun isMobile(): Boolean = "mobile" == BuildConfig.FLAVOR_mode

    @Suppress("DEPRECATION")
    @JvmStatic
    fun isFullscreen(activity: Activity): Boolean {
        return isLeanback() ||
            (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0
    }

    @JvmStatic
    fun format(builder: StringBuilder, formatter: Formatter, timeMs: Long): String {
        return try {
            androidx.media3.common.util.Util.getStringForTime(builder, formatter, timeMs)
        } catch (e: Exception) {
            ""
        }
    }

    @JvmStatic
    fun timeMs(timeMs: Long): String {
        val sb = StringBuilder()
        return format(sb, Formatter(sb, Locale.getDefault()), timeMs)
    }
}
