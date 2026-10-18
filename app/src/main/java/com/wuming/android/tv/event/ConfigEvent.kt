package com.wuming.android.tv.event

import com.wuming.android.tv.setting.AppSettings
import org.greenrobot.eventbus.EventBus

data class ConfigEvent(private val typeValue: Type) {
    enum class Type { COMMON, VOD, LIVE, WALL, BOOT }

    fun type(): Type = typeValue
    val isVod: Boolean get() = typeValue == Type.VOD
    val isLive: Boolean get() = typeValue == Type.LIVE

    companion object {
        @JvmStatic
        fun common() = EventBus.getDefault().post(ConfigEvent(Type.COMMON))

        @JvmStatic
        fun vod() = EventBus.getDefault().post(ConfigEvent(Type.VOD))

        @JvmStatic
        fun live() = EventBus.getDefault().post(ConfigEvent(Type.LIVE))

        @JvmStatic
        fun wall() = EventBus.getDefault().post(ConfigEvent(Type.WALL))

        @JvmStatic
        fun boot() {
            EventBus.getDefault().post(ConfigEvent(Type.BOOT))
            AppSettings.putBootLive(false)
        }
    }
}
