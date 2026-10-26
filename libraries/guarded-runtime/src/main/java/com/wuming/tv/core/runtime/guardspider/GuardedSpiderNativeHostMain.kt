package com.wuming.tv.core.runtime.guardspider

import com.google.gson.Gson

/** Shared worker entry point; optional native execution belongs to the consuming edition. */
object GuardedSpiderNativeHostMain {
    @JvmStatic
    fun main(args: Array<String?>) {
        val provider = GuardedNativeProviders.load()
        if (provider != null) {
            provider.runHost(args)
            return
        }
        println(Gson().toJson(mapOf(
            "ok" to false,
            "error" to mapOf("kind" to "java.lang.UnsupportedOperationException",
                "message" to GuardedNativeCryptoRuntime.REASON,
                "detail" to GuardedNativeCryptoRuntime.REASON)
        )))
    }
}
