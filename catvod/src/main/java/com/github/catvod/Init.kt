package com.github.catvod

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context

object Init {

    @SuppressLint("StaticFieldLeak")
    @Volatile
    private var context: Context? = null

    @JvmStatic
    fun set(context: Context?) {
        if (context == null) {
            this.context = null
            return
        }
        val applicationContext = if (context is Application) context else context.applicationContext
        this.context = applicationContext ?: context
    }

    @JvmStatic
    fun context(): Context? = context
}
