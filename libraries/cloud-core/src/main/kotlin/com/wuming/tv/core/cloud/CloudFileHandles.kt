package com.wuming.tv.core.cloud

import com.wuming.tv.core.cache.BoundedReferenceCache
import java.util.UUID

/** Reference promotion/expiry workflow; a host supplies its encrypted vault. */
class CloudFileHandles<T : Any>(private val providerId: String, private val referenceType: Class<T>,
                              private val vault: CloudHandleVault) {
    private val entries = BoundedReferenceCache<String, T>(CloudHandleVault.MAX_HANDLES)
    fun remember(value: T): String = UUID.randomUUID().toString().also { entries.put(it, value) }
    fun persist(handle: String): Boolean {
        val value = entries.get(handle) ?: vault.get(providerId, handle, referenceType) ?: return false
        vault.put(providerId, handle, value)
        return true
    }
    fun require(handle: String): T = entry(handle) ?: throw IllegalArgumentException("Cloud item expired; reopen the folder or share")
    fun clear() = entries.clear()
    fun clearPersisted() { entries.clear(); vault.clearProvider(providerId) }
    private fun entry(handle: String): T? = entries.get(handle)
        ?: vault.get(providerId, handle, referenceType)?.also { entries.put(handle, it) }
}
