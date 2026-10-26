package com.wuming.tv.core.runtime.guardspider

import java.nio.file.Path

/** Stable native-call facade; an edition may supply the execution port through ServiceLoader. */
class GuardedNativeCryptoRuntime(
    abi: GuardedSpiderAbi, library: Path, guard: Path, dataDirectory: Path
) : GuardedNativeSession {
    private val session = (GuardedNativeProviders.load() ?: throw UnsupportedOperationException(REASON))
        .open(abi, library, guard, dataDirectory)

    override fun callString(signature: String, vararg arguments: Any?): String = session.callString(signature, *arguments)
    override fun callIntArray(signature: String, value: IntArray): IntArray = session.callIntArray(signature, value)
    override fun close() = session.close()

    companion object {
        const val REASON = "Native guarded-provider execution is not included in this distribution. Choose another source."
    }
}
