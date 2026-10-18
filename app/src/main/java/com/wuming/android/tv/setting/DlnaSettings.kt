package com.wuming.android.tv.setting

import com.github.catvod.utils.Prefers

/** Receiver discovery name, independent of the launcher label and Android device name. */
object DlnaSettings {
    private const val KEY = "dlna_display_name"
    const val DEFAULT_NAME = "_私人电视-非公共投屏设备-请勿连接或投屏-正在使用中"
    const val MAX_CODE_POINTS = 80

    @JvmStatic
    fun getName(): String = normalize(Prefers.getString(KEY))

    @JvmStatic
    fun setName(name: String?) { Prefers.put(KEY, normalize(name)) }

    @JvmStatic
    fun normalize(name: String?): String {
        if (name == null) return DEFAULT_NAME
        val clean = StringBuilder()
        name.codePoints()
            .filter { cp -> cp >= 32 && cp != 127 && Character.isValidCodePoint(cp)
                && !(cp in 0xd800..0xdfff) && cp != 0xfffe && cp != 0xffff }
            .limit(MAX_CODE_POINTS.toLong())
            .forEach { clean.appendCodePoint(it) }
        val result = clean.toString().trim()
        return if (result.isEmpty()) DEFAULT_NAME else result
    }
}
