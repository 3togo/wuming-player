package com.github.catvod.crawler

import android.text.TextUtils
import android.util.Log

object SpiderDebug {

    private val TAG: String = SpiderDebug::class.java.simpleName

    @JvmStatic
    fun log(th: Throwable?) {
        if (th != null) Log.e(TAG, "Spider error", th)
    }

    @JvmStatic
    fun log(msg: String?) {
        if (!TextUtils.isEmpty(msg)) Log.d(TAG, msg!!)
    }

    @JvmStatic
    fun log(tag: String, msg: String?, vararg args: Any?) {
        if (!TextUtils.isEmpty(msg)) Log.d(tag, String.format(msg!!, *args))
    }
}
