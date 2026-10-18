package com.wuming.android.tv.event

import com.wuming.android.tv.bean.Vod
import org.greenrobot.eventbus.EventBus

class RefreshEvent(val type: Type) {
    enum class Type {
        HOME, CATEGORY, HISTORY, KEEP, SIZE, LIVE, DETAIL, PLAYER, SUBTITLE, DANMAKU, VOD,
        SEARCH_RESULT, QUICK_SEARCH, QUICK_SEARCH_WORD, QUICK_SEARCH_SELECT, QUICK_SEARCH_WORD_CHANGE,
    }

    var path: String? = null
        private set
    var vod: Vod? = null
        private set
    var obj: Any? = null

    constructor(type: Type, path: String?) : this(type) {
        this.path = path
    }

    constructor(type: Type, obj: Any?) : this(type) {
        this.obj = obj
    }

    companion object {
        private fun vodEvent(vod: Vod?) = RefreshEvent(Type.VOD).apply { this.vod = vod }

        @JvmStatic
        fun home() = EventBus.getDefault().post(RefreshEvent(Type.HOME))

        @JvmStatic
        fun category() = EventBus.getDefault().post(RefreshEvent(Type.CATEGORY))

        @JvmStatic
        fun history() = EventBus.getDefault().post(RefreshEvent(Type.HISTORY))

        @JvmStatic
        fun keep() = EventBus.getDefault().post(RefreshEvent(Type.KEEP))

        @JvmStatic
        fun size() = EventBus.getDefault().post(RefreshEvent(Type.SIZE))

        @JvmStatic
        fun live() = EventBus.getDefault().post(RefreshEvent(Type.LIVE))

        @JvmStatic
        fun detail() = EventBus.getDefault().post(RefreshEvent(Type.DETAIL))

        @JvmStatic
        fun player() = EventBus.getDefault().post(RefreshEvent(Type.PLAYER))

        @JvmStatic
        fun subtitle(path: String?) = EventBus.getDefault().post(RefreshEvent(Type.SUBTITLE, path))

        @JvmStatic
        fun danmaku(path: String?) = EventBus.getDefault().post(RefreshEvent(Type.DANMAKU, path))

        @JvmStatic
        fun vod(vod: Vod?) = EventBus.getDefault().post(vodEvent(vod))
    }
}
