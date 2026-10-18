package com.wuming.android.tv.event

import org.greenrobot.eventbus.EventBus

data class ServerEvent(private val typeValue: Type, private val textValue: String, private val nameValue: String = "") {
    enum class Type { SEARCH, PUSH, SETTING }

    fun type(): Type = typeValue
    fun text(): String = textValue
    fun name(): String = nameValue

    companion object {
        @JvmStatic
        fun search(text: String) = EventBus.getDefault().post(ServerEvent(Type.SEARCH, text))

        @JvmStatic
        fun push(text: String) = EventBus.getDefault().post(ServerEvent(Type.PUSH, text))

        @JvmStatic
        fun setting(text: String) = EventBus.getDefault().post(ServerEvent(Type.SETTING, text))

        @JvmStatic
        fun setting(text: String, name: String) = EventBus.getDefault().post(ServerEvent(Type.SETTING, text, name))
    }
}
