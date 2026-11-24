package com.wuming.android.tv.api.config

import com.wuming.android.tv.App
import com.wuming.android.tv.bean.Config
import com.wuming.android.tv.impl.Callback
import com.wuming.android.tv.utils.Task

/** Android persistence/loader adapter for restoring the VOD source's live section. */
object DefaultLiveSource {
    @JvmStatic fun load(callback: Callback) {
        Task.execute {
            try {
                val config = Config.find(Config.vod(), 1)
                App.post { LiveConfig.load(config, callback) }
            } catch (error: Exception) {
                App.post { callback.error(error.message.orEmpty()) }
            }
        }
    }
}
