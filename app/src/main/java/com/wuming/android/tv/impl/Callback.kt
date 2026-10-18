package com.wuming.android.tv.impl

import okhttp3.Call
import okhttp3.Response
import java.io.IOException

open class Callback : okhttp3.Callback {
    open fun success() {}
    open fun success(result: String) {}
    open fun error() {}
    open fun start() {}
    open fun error(msg: String) {}

    override fun onFailure(call: Call, e: IOException) {}

    @Throws(IOException::class)
    override fun onResponse(call: Call, response: Response) {}
}
