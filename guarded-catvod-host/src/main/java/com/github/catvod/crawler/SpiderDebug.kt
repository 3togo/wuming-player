package com.github.catvod.crawler

object SpiderDebug {
    @JvmStatic
    fun log(message: String?) {
        System.err.println(if (message == null) "null" else message)
    }

    @JvmStatic
    fun log(failure: Throwable?) {
        if (failure != null) failure.printStackTrace(System.err)
    }
}
