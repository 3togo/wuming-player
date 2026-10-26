package com.wuming.tv.core.source

import com.wuming.tv.core.base.EditionSettings

/** Public builds reject native SDK inputs before network parsing or player launch. */
object PlaybackProtocolPolicy {
    @JvmStatic fun unsupported(value: String?): Boolean =
        unsupported(value, EditionSettings.current.nativeProtocols)

    internal fun unsupported(value: String?, nativeProtocols: Boolean): Boolean =
        !nativeProtocols && RemovedSdkProtocols.matches(value)
}
