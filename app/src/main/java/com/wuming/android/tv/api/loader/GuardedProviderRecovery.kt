package com.wuming.android.tv.api.loader

import android.content.Context
import dalvik.system.DexClassLoader
import java.io.File
import java.security.MessageDigest

/** Android 14 compatibility for the measured szyyds guarded provider, never arbitrary caches. */
internal object GuardedProviderRecovery {
    private const val PROVIDER_SHA256 = "b2755622dc43d9181b9d1a4bd9e11251fea15048fa2f82b91adafc57f1771551"
    private const val PAYLOAD_SHA256 = "4b8ec2c46d0790d0737e6a2e30a217837d49e319a28b2ef819a1547dd7e0244e"

    @JvmStatic
    fun matches(provider: File, payload: File): Boolean =
        provider.isFile && payload.isFile &&
            digest(provider) == PROVIDER_SHA256 && digest(payload) == PAYLOAD_SHA256

    @JvmStatic
    fun recover(loader: ClassLoader, context: Context, provider: File): Boolean {
        val payload = File(context.cacheDir, "sharedb/config.db")
        if (!matches(provider, payload)) return false
        val init = loader.loadClass("com.github.catvod.spider.Init")
        if (init.getMethod("loader").invoke(null) != null) return false
        check(payload.setReadOnly()) { "Unable to protect generated provider code" }
        val nested = DexClassLoader(payload.path, payload.parent, payload.parent, loader)
        val origin = nested.loadClass("com.github.catvod.spider.InitOrigin")
        origin.getMethod("setClass", Class::class.java)
            .invoke(null, loader.loadClass("com.github.catvod.spider.DexNative"))
        val singleton = init.getMethod("get").invoke(null)
        // This field belongs to the exact provider binary pinned above.
        val field = init.getDeclaredField("oOoOoOo0oOo0o0oO").apply { isAccessible = true }
        field.set(singleton, nested)
        try {
            origin.getMethod("init", Context::class.java).invoke(null, context)
            check(init.getMethod("loader").invoke(null) === nested)
            return true
        } catch (failure: Throwable) {
            field.set(singleton, null)
            throw failure
        }
    }

    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
