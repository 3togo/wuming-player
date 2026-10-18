package com.wuming.android.tv.bean

import android.graphics.Color
import com.github.catvod.utils.Trans
import java.util.regex.Matcher

class DanmakuData(matcher: Matcher, density: Float) {
    private companion object {
        // Video-overlay contrast colors stay independent of the application theme.
        const val OVERLAY_LIGHT_COLOR = Color.WHITE
    }

    private var type: Int = 0
    private var color: Int = 0
    private var shadow: Int = 0
    private var time: Long = 0
    private var size: Float = 0f
    private var text: String? = null

    init {
        param(matcher.group(1).orEmpty(), density)
        this.text = matcher.group(2)
        trans()
    }

    private fun param(param: String, density: Float) {
        val params = param.split(",")
        if (params.size < 4) throw Exception()
        this.type = runCatching { params[1].toInt() }.getOrDefault(0)
        this.time = runCatching { (params[0].toFloat() * 1000).toLong() }.getOrDefault(0L)
        this.size = runCatching { params[2].toFloat() * (density - 0.6f) }.getOrDefault(0f)
        this.color = runCatching { ((0x00000000FF000000L or params[3].toLong()) and 0x00000000FFFFFFFFL).toInt() }.getOrDefault(OVERLAY_LIGHT_COLOR)
        this.shadow = if (color <= Color.BLACK) OVERLAY_LIGHT_COLOR else Color.BLACK
    }

    fun getType(): Int = type

    fun getShadow(): Int = shadow

    fun getColor(): Int = color

    fun getTime(): Long = time

    fun getSize(): Float = size

    fun getText(): String = BeanUtils.emptyIfNull(text)
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&gt;", ">")
        .replace("&lt;", "<")

    fun trans() {
        if (Trans.pass()) return
        this.text = Trans.s2t(text)
    }
}
