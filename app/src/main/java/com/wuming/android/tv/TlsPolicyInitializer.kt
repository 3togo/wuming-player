package com.wuming.android.tv

import android.content.Context
import androidx.startup.Initializer
import com.wuming.android.tv.setting.AppSettings

class TlsPolicyInitializer : Initializer<Unit> {

    override fun create(context: Context) {
        AppSettings.initializeTlsPolicy()
    }

    override fun dependencies(): List<Class<out Initializer<*>>> =
        listOf(Startup::class.java)
}
