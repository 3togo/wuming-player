package com.wuming.tv.core.catalog

object DetailAvailabilityPolicy {
    @JvmStatic fun hasPlayableEpisodes(urls: Iterable<String>): Boolean = urls.any { it.isNotBlank() }
    @JvmStatic fun canCache(present: Boolean, folder: Boolean, action: Boolean, playable: java.util.function.BooleanSupplier): Boolean =
        present && (folder || action || playable.asBoolean)
}
