package com.wuming.android.tv.api.loader

import com.wuming.tv.core.source.SpiderDigest
import java.io.File
import java.io.IOException

/** Host supplies the private cache directory; verification and recovery are shared. */
class ProviderJarCache(directory: File, referenceKey: String) {
    private val shared = com.wuming.tv.core.source.DexProviderJarCache(directory, referenceKey)
    @Throws(IOException::class) fun accepts(artifact: File, digest: SpiderDigest?): Boolean = shared.accepts(artifact, digest)
    fun recover(digest: SpiderDigest?): File? = shared.recover(digest)
    @Throws(IOException::class) fun promote(initialized: File, digest: SpiderDigest?) = shared.promote(initialized, digest)
}
