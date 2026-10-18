package com.wuming.android.tv

import android.content.Context
import androidx.startup.Initializer
import com.wuming.android.tv.setting.AppSettings
import com.wuming.android.tv.ui.activity.CrashActivity
import com.github.catvod.bean.Doh
import com.github.catvod.net.OkHttp
import com.orhanobut.logger.AndroidLogAdapter
import com.orhanobut.logger.Logger
import com.orhanobut.logger.PrettyFormatStrategy
import org.greenrobot.eventbus.EventBus
import cat.ereza.customactivityoncrash.config.CaocConfig

class Startup : Initializer<Unit> {

    override fun create(context: Context) {
        if (!BuildConfig.DEBUG) {
            CaocConfig.Builder.create()
                .trackActivities(true)
                .backgroundMode(CaocConfig.BACKGROUND_MODE_SILENT)
                .errorActivity(CrashActivity::class.java)
                .apply()
        }
        Logger.addLogAdapter(
            AndroidLogAdapter(
                PrettyFormatStrategy.newBuilder()
                    .methodCount(0)
                    .showThreadInfo(false)
                    .tag("TV")
                    .build()
            )
        )
        EventBus.builder().addIndex(Class.forName("com.wuming.android.tv.event.EventIndex").getDeclaredConstructor().newInstance() as org.greenrobot.eventbus.meta.SubscriberInfoIndex).installDefaultEventBus()
        OkHttp.dns().setDoh(Doh.objectFrom(AppSettings.getDoh()))
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
