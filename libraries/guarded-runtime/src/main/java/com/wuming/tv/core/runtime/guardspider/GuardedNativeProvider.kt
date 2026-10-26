package com.wuming.tv.core.runtime.guardspider

import java.nio.file.Path
import java.util.ServiceLoader

/** Optional native execution port. Public distributions contain no implementation. */
interface GuardedNativeProvider {
    fun runHost(arguments: Array<String?>)
    fun open(abi: GuardedSpiderAbi, library: Path, guard: Path, dataDirectory: Path): GuardedNativeSession
}

interface GuardedNativeSession : AutoCloseable {
    fun callString(signature: String, vararg arguments: Any?): String
    fun callIntArray(signature: String, value: IntArray): IntArray
}

internal object GuardedNativeProviders {
    fun load(): GuardedNativeProvider? {
        val providers = ServiceLoader.load(GuardedNativeProvider::class.java).iterator()
        if (!providers.hasNext()) return null
        val provider = providers.next()
        check(!providers.hasNext()) { "Multiple guarded native providers are installed" }
        return provider
    }
}
