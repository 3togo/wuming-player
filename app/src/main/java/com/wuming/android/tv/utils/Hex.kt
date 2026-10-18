package com.wuming.android.tv.utils

/** Retains the Android/Java caller API; byte encoding is owned by base-core. */
object Hex {
    @JvmStatic fun encodeLower(data: ByteArray?): String = com.wuming.tv.core.base.Hex.encodeLower(data)
    @JvmStatic fun encodeUpper(data: ByteArray?): String = com.wuming.tv.core.base.Hex.encodeUpper(data)
    @JvmStatic fun encodeUpperColon(data: ByteArray?): String = com.wuming.tv.core.base.Hex.encodeUpperColon(data)
}
