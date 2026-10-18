package com.wuming.android.tv.player.engine

import android.net.Uri
import androidx.media3.common.MediaMetadata
import com.wuming.android.tv.bean.Danmaku
import com.wuming.android.tv.bean.Drm
import com.wuming.android.tv.bean.Result
import com.wuming.android.tv.bean.Sub
import com.wuming.android.tv.player.PlayerHelper
import com.wuming.android.tv.setting.PlayerSettings
import com.wuming.android.tv.utils.UrlUtil
import com.google.common.net.HttpHeaders

open class PlaySpec private constructor(
    key: String?,
    url: String?,
    headers: Map<String, String>?,
    format: String?,
    drm: Drm?,
    subs: List<Sub>?,
    danmakus: List<Danmaku?>?,
    metadata: MediaMetadata?,
) {
    private var desktopAssistanceAllowed = false

    fun withDesktopAssistance(allowed: Boolean): PlaySpec = apply { desktopAssistanceAllowed = allowed }

    fun allowsDesktopAssistance(): Boolean = desktopAssistanceAllowed

    private var key: String? = key
    private var url: String? = url
    private var headers: MutableMap<String, String> = HashMap()
    private var format: String? = format
    private val drm = drm
    private val subs: MutableList<Sub> = if (subs == null) ArrayList() else ArrayList(subs)
    private val danmakus: MutableList<Danmaku> = ArrayList()
    private var metadata = metadata

    init {

        setHeaders(headers)
        danmakus?.forEach {
            if (it != null) this.danmakus.add(copyDanmaku(it))
        }
    }

    open fun getKey(): String? = key

    open fun setKey(key: String?) {
        this.key = key
    }

    open fun getUrl(): String? = url

    open fun setUrl(url: String?) {
        this.url = url
    }

    open fun getUri(): Uri = UrlUtil.uri(url)

    open fun getHeaders(): MutableMap<String, String> = headers

    open fun setHeaders(headers: Map<String, String>?) {
        this.headers = if (headers == null) HashMap() else HashMap(headers)
    }

    open fun getFormat(): String? = format

    open fun setFormat(format: String?) {
        this.format = format
    }

    open fun getDrm(): Drm? = drm

    open fun getSubs(): MutableList<Sub> = subs

    open fun getDanmakus(): MutableList<Danmaku> = danmakus

    open fun getMetadata(): MediaMetadata? = metadata

    open fun setMetadata(metadata: MediaMetadata?) {
        this.metadata = metadata
    }

    open fun checkUa(): PlaySpec {
        if (headers.keys.none {
                HttpHeaders.USER_AGENT.equals(it, true)
            }
        ) {
            val userAgent = PlayerSettings.getUa() ?: ""
            headers[HttpHeaders.USER_AGENT] = if (userAgent.isEmpty()) PlayerHelper.getDefaultUa() else userAgent
        }
        return this
    }

    open fun setSub(sub: Sub?) {
        if (sub != null && !subs.contains(sub)) subs.add(0, sub)
    }

    open fun setDanmaku(item: Danmaku?) {
        val selectedUrl = item?.getUrl() ?: ""
        if (selectedUrl.isNotEmpty() && !danmakus.contains(item)) danmakus.add(0, copyDanmaku(item!!))
        danmakus.forEach {
            it.setSelected(selectedUrl.isNotEmpty() && it.getUrl() == selectedUrl)
        }
    }

    companion object {
        @JvmStatic fun from(
            key: String?,
            url: String?,
            headers: Map<String, String>?,
            metadata: MediaMetadata?,
        ): PlaySpec = PlaySpec(key, url, headers, null, null, null, null, metadata)

        @JvmStatic fun from(
            result: Result,
            key: String?,
            metadata: MediaMetadata?,
        ): PlaySpec = PlaySpec(key, result.realUrl, result.header, result.format, result.drm, result.subs, result.danmaku, metadata)

        @JvmStatic fun fromParse(
            result: Result,
            key: String?,
            metadata: MediaMetadata?,
        ): PlaySpec = PlaySpec(key, null, null, result.format, result.drm, result.subs, result.danmaku, metadata)

        private fun copyDanmaku(item: Danmaku): Danmaku =
            Danmaku.from(item.getUrl()).also {
                it.setName(item.getName())
                it.setSelected(item.isSelected())
            }
    }
}
