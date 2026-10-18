package com.wuming.android.tv.player.thirdparty

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Parcelable

import com.wuming.android.tv.App
import com.wuming.android.tv.bean.BeanUtils

import org.json.JSONObject

import java.net.URLEncoder

/**
 * External player launcher utility supporting MX Player, VLC, Kodi, and ReexPlayer.
 * Ported from TVBoxOS-Mobile with adaptations for TV project architecture.
 */
object ExternalPlayer {

    private val MX_PACKAGES = arrayOf("com.mxtech.videoplayer.pro", "com.mxtech.videoplayer.ad")
    private val MX_ACTIVITIES = arrayOf("com.mxtech.videoplayer.ActivityScreen", "com.mxtech.videoplayer.ad.ActivityScreen")

    private const val VLC_PACKAGE = "org.videolan.vlc"
    private const val VLC_ACTIVITY = "org.videolan.vlc.gui.video.VideoPlayerActivity"

    private const val KODI_PACKAGE = "org.xbmc.kodi"
    private val KODI_ACTIVITIES = arrayOf("org.xbmc.kodi.Main", "org.xbmc.kodi.Splash")

    private const val REEX_PACKAGE = "xyz.re.player.ex"
    private const val REEX_ACTIVITY = "xyz.re.player.ex.MainActivity"

    @JvmStatic
    fun isMXInstalled(): Boolean = findPackage(MX_PACKAGES) >= 0

    @JvmStatic
    fun isVLCInstalled(): Boolean = isPackageEnabled(VLC_PACKAGE)

    @JvmStatic
    fun isKodiInstalled(): Boolean = isPackageEnabled(KODI_PACKAGE)

    @JvmStatic
    fun isReexInstalled(): Boolean = isPackageEnabled(REEX_PACKAGE)

    @JvmStatic
    fun runMX(activity: Activity, url: String, title: String, subtitle: String, headers: Map<String, String>?): Boolean {
        val idx = findPackage(MX_PACKAGES)
        if (idx < 0) return false
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setClassName(MX_PACKAGES[idx], MX_ACTIVITIES[idx])
            if (headers != null && !headers.isEmpty()) {
                val sb = StringBuilder(url).append("|")
                var i = 0
                for (entry in headers.entries) {
                    sb.append(entry.key).append("=").append(URLEncoder.encode(entry.value, "UTF-8"))
                    if (i < headers.size - 1) sb.append("&")
                    i++
                }
                intent.setData(Uri.parse(sb.toString()))
            } else {
                intent.setData(Uri.parse(url))
            }
            intent.putExtra("title", title)
            if (BeanUtils.isNotEmpty(subtitle)) {
                val parcels = arrayOf<Parcelable>(Uri.parse(subtitle))
                intent.putExtra("subs", parcels)
                intent.putExtra("subs.enable", parcels)
            }
            activity.startActivity(intent)
            return true
        } catch (e: Exception) {
            return false
        }
    }

    @JvmStatic
    fun runVLC(activity: Activity, url: String, title: String, subtitle: String, progress: Long): Boolean {
        if (!isVLCInstalled()) return false
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setClassName(VLC_PACKAGE, VLC_ACTIVITY)
            intent.setDataAndTypeAndNormalize(Uri.parse(url), "video/*")
            intent.putExtra("title", title)
            if (BeanUtils.isNotEmpty(subtitle)) intent.putExtra("subtitles_location", subtitle)
            if (progress > 0) {
                intent.putExtra("from_start", false)
                intent.putExtra("position", progress)
            }
            activity.startActivity(intent)
            return true
        } catch (e: ActivityNotFoundException) {
            return false
        }
    }

    @JvmStatic
    fun runKodi(activity: Activity, url: String, title: String, subtitle: String, headers: Map<String, String>?): Boolean {
        if (!isKodiInstalled()) return false
        val playbackUri = try {
            if (headers != null && !headers.isEmpty()) {
                val sb = StringBuilder(url).append("|")
                var i = 0
                for (entry in headers.entries) {
                    sb.append(entry.key).append("=").append(URLEncoder.encode(entry.value, "UTF-8"))
                    if (i < headers.size - 1) sb.append("&")
                    i++
                }
                Uri.parse(sb.toString())
            } else {
                Uri.parse(url)
            }
        } catch (e: Exception) {
            return false
        }
        for (entry in KODI_ACTIVITIES) {
            try {
                val intent = Intent(Intent.ACTION_VIEW)
                intent.setClassName(KODI_PACKAGE, entry)
                intent.data = playbackUri
                intent.putExtra("title", title)
                intent.putExtra("name", title)
                if (BeanUtils.isNotEmpty(subtitle)) intent.putExtra("subs", subtitle)
                activity.startActivity(intent)
                return true
            } catch (e: Exception) {
                // A missing or inaccessible Kodi entry must not block the fallback.
            }
        }
        return false
    }

    @JvmStatic
    fun runReex(activity: Activity, url: String, title: String, subtitle: String, headers: Map<String, String>?): Boolean {
        if (!isReexInstalled()) return false
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setComponent(ComponentName(REEX_PACKAGE, REEX_ACTIVITY))
            intent.setData(Uri.parse(url))
            intent.putExtra("title", title)
            intent.putExtra("name", title)
            intent.putExtra("reex.extra.title", title)
            if (headers != null && !headers.isEmpty()) {
                val json = JSONObject()
                for (entry in headers.entries) json.put(entry.key, entry.value.trim())
                intent.putExtra("reex.extra.http_header", json.toString())
            }
            if (BeanUtils.isNotEmpty(subtitle)) intent.putExtra("reex.extra.subtitle", subtitle)
            activity.startActivity(intent)
            return true
        } catch (e: Exception) {
            return false
        }
    }

    private fun findPackage(packages: Array<String>): Int {
        for (i in packages.indices) {
            if (isPackageEnabled(packages[i])) return i
        }
        return -1
    }

    private fun isPackageEnabled(packageName: String): Boolean {
        try {
            val info: ApplicationInfo = App.get().packageManager.getApplicationInfo(packageName, 0)
            return info.enabled
        } catch (e: PackageManager.NameNotFoundException) {
            return false
        }
    }
}
