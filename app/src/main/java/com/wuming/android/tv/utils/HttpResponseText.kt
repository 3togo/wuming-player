package com.wuming.android.tv.utils

import okhttp3.Response
import java.io.IOException

/** Android/Java caller API for shared bounded metadata reads. */
object HttpResponseText {
    @JvmStatic
    @Throws(IOException::class)
    fun read(response: Response, maxBytes: Int): String = com.wuming.tv.core.base.BoundedHttpText.read(response, maxBytes)
}
