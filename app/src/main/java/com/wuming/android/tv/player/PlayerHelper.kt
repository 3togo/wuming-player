package com.wuming.android.tv.player

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Bundle

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Util

import com.wuming.android.tv.App
import com.wuming.android.tv.BuildConfig
import com.wuming.android.tv.bean.BeanUtils
import com.wuming.android.tv.player.thirdparty.ExternalPlayer
import com.wuming.android.tv.utils.FileUtil

import java.util.ArrayList
import java.util.StringJoiner
import java.util.function.LongConsumer

object PlayerHelper {

    @JvmStatic
    fun getDefaultUa(): String = Util.getUserAgent(App.get(), BuildConfig.APPLICATION_ID)

    @JvmStatic
    fun getSubtitleMimeType(path: String?): String {
        if (BeanUtils.isEmpty(path)) return ""
        if (path!!.endsWith(".vtt")) return MimeTypes.TEXT_VTT
        if (path.endsWith(".ssa") || path.endsWith(".ass")) return MimeTypes.TEXT_SSA
        if (path.endsWith(".ttml") || path.endsWith(".xml") || path.endsWith(".dfxp")) return MimeTypes.APPLICATION_TTML
        return MimeTypes.APPLICATION_SUBRIP
    }

    @JvmStatic
    fun toBundle(headers: Map<String, String>): Bundle {
        val bundle = Bundle()
        for ((key, value) in headers) bundle.putString(key, value)
        return bundle
    }

    @JvmStatic
    fun describeFormat(format: Format): String {
        val joiner = StringJoiner(",")
        if (format.id != null) joiner.add(format.id)
        if (format.codecs != null) joiner.add(format.codecs)
        if (format.sampleMimeType != null) joiner.add(format.sampleMimeType)
        if (format.containerMimeType != null) joiner.add(format.containerMimeType)
        return joiner.toString()
    }

    @JvmStatic
    fun share(activity: Activity, url: String?, headers: Map<String, String>, title: CharSequence) {
        try {
            if (url == null || url.isEmpty()) return
            val bundle = toBundle(headers)
            val intent = Intent(Intent.ACTION_SEND)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.putExtra(Intent.EXTRA_TEXT, url)
            intent.putExtra("extra_headers", bundle)
            intent.putExtra("title", title).putExtra("name", title)
            intent.setType("text/plain")
            activity.startActivity(getChooser(intent))
        } catch (ignored: Exception) {
        }
    }

    @JvmStatic
    fun choose(activity: Activity, url: String?, headers: Map<String, String>, isVod: Boolean, position: Long, title: CharSequence) {
        try {
            if (url == null || url.isEmpty()) return
            val list = ArrayList<String>()
            for ((key, value) in headers) {
                list.add(key)
                list.add(value)
            }
            val data = if (url.startsWith("file://") || url.startsWith("/")) FileUtil.getShareUri(url) else Uri.parse(url)
            val intent = Intent(Intent.ACTION_VIEW)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.setDataAndType(data, "video/*")
            intent.putExtra("title", title).putExtra("return_result", isVod)
            intent.putExtra("headers", list.toArray(arrayOfNulls<String>(0)))
            if (isVod) intent.putExtra("position", position.toInt())
            activity.startActivityForResult(getChooser(intent), 1001)
        } catch (ignored: Exception) {
        }
    }

    @JvmStatic
    fun onExternalResult(data: Intent?, onNext: Runnable, seekTo: LongConsumer) {
        try {
            if (data == null || data.getExtras() == null) return
            val position = data.getExtras()!!.getInt("position", 0).toLong()
            val endBy = data.getExtras()!!.getString("end_by", "")
            if ("playback_completion" == endBy) App.post(onNext)
            if ("user" == endBy) seekTo.accept(position)
        } catch (ignored: Exception) {
        }
    }

    @JvmStatic
    fun runExternalPlayer(playerType: Int, activity: Activity, url: String, title: String, subtitle: String, headers: Map<String, String>, progress: Long): Boolean {
        when (playerType) {
            10 -> return ExternalPlayer.runMX(activity, url, title, subtitle, headers)
            11 -> return ExternalPlayer.runReex(activity, url, title, subtitle, headers)
            12 -> return ExternalPlayer.runKodi(activity, url, title, subtitle, headers)
            14 -> return ExternalPlayer.runVLC(activity, url, title, subtitle, progress)
            else -> return false
        }
    }

    @JvmStatic
    fun getExternalPlayerName(playerType: Int): String {
        when (playerType) {
            10 -> return "MX Player"
            11 -> return "Reex Player"
            12 -> return "Kodi"
            14 -> return "VLC"
            else -> return ""
        }
    }

    @JvmStatic
    fun isExternalPlayerInstalled(playerType: Int): Boolean {
        when (playerType) {
            10 -> return ExternalPlayer.isMXInstalled()
            11 -> return ExternalPlayer.isReexInstalled()
            12 -> return ExternalPlayer.isKodiInstalled()
            14 -> return ExternalPlayer.isVLCInstalled()
            else -> return false
        }
    }

    @JvmStatic
    private fun getChooser(intent: Intent): Intent {
        val components = ArrayList<ComponentName>()
        for (resolveInfo in App.get().getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)) {
            val pkgName = resolveInfo.activityInfo.packageName
            if (pkgName == App.get().getPackageName()) {
                components.add(ComponentName(pkgName, resolveInfo.activityInfo.name))
            }
        }
        return Intent.createChooser(intent, null).putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, components.toArray(arrayOfNulls<ComponentName>(0)))
    }
}
