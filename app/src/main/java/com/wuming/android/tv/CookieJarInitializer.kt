package com.wuming.android.tv

import android.content.Context
import androidx.startup.Initializer

class CookieJarInitializer : Initializer<Unit> {

    override fun create(context: Context) {
        com.github.catvod.net.PersistentCookieJar.create(context)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> =
        listOf(Startup::class.java)
}
