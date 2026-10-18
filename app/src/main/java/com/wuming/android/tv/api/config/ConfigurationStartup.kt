package com.wuming.android.tv.api.config

import com.wuming.android.tv.impl.Callback

/** Startup order shared by mobile, TV and TV Lite. Default selection stays in Config. */
object ConfigurationStartup {
    @JvmStatic
    fun load(callback: Callback) {
        VodConfig.get().init().load(callback)
        LiveConfig.get().init().load()
        WallConfig.get().init()
    }
}
