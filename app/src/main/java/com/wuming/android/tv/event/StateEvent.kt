package com.wuming.android.tv.event

import org.greenrobot.eventbus.EventBus

data class StateEvent(private val typeValue: Type) {
    enum class Type { EMPTY, PROGRESS, CONTENT }

    fun type(): Type = typeValue

    companion object {
        @JvmStatic
        fun empty() = EventBus.getDefault().post(StateEvent(Type.EMPTY))

        @JvmStatic
        fun progress() = EventBus.getDefault().post(StateEvent(Type.PROGRESS))

        @JvmStatic
        fun content() = EventBus.getDefault().post(StateEvent(Type.CONTENT))
    }
}
