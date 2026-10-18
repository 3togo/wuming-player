package com.wuming.tv.core.runtime.guardspider

import java.nio.file.Path

/** Retains the host ABI while rejecting native emulation removed from the public build. */
@Suppress("UNUSED_PARAMETER")
class GuardedNativeCryptoRuntime(
    abi: GuardedSpiderAbi, library: Path, guard: Path, dataDirectory: Path
) : AutoCloseable {
    init { throw UnsupportedOperationException(REASON) }
    fun callString(signature: String, vararg arguments: Any?): String = throw UnsupportedOperationException(REASON)
    fun callIntArray(signature: String, value: IntArray): IntArray = throw UnsupportedOperationException(REASON)
    override fun close() = Unit

    companion object {
        const val REASON = "Native guarded-provider emulation is unavailable in the public build. Choose another source."
    }
}
