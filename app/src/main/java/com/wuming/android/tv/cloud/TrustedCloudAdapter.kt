package com.wuming.android.tv.cloud

/**
 * Narrow host-owned capability implemented by provider code compiled with the
 * application. Downloaded spider JARs are deliberately not adapters.
 */
interface TrustedCloudAdapter {
    fun provider(): CloudStorageProvider

    @Throws(Exception::class)
    fun clear(records: List<@JvmSuppressWildcards CloudCacheRecord>): CloudStorageManager.Result
}
