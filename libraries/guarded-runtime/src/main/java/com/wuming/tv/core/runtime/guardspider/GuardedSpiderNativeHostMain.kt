package com.wuming.tv.core.runtime.guardspider

import com.google.gson.Gson

/** Explicit failure response for the removed desktop ARM-emulation worker. */
object GuardedSpiderNativeHostMain {
    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun main(args: Array<String?>) {
        println(Gson().toJson(mapOf(
            "ok" to false,
            "error" to mapOf("kind" to "java.lang.UnsupportedOperationException",
                "message" to GuardedNativeCryptoRuntime.REASON,
                "detail" to GuardedNativeCryptoRuntime.REASON)
        )))
    }
}
