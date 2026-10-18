package com.wuming.android.tv.cloud.opendrive

internal class OpenDriveFileHandles<T : Any>(providerId: String, referenceType: Class<T>) {
    private val shared = com.wuming.tv.core.cloud.CloudFileHandles(providerId, referenceType, OpenDriveHandleVault.shared)
    fun remember(value: T): String = shared.remember(value)
    fun persist(handle: String): Boolean = shared.persist(handle)
    fun require(handle: String): T = shared.require(handle)
    fun clear() = shared.clear()
    fun clearPersisted() = shared.clearPersisted()
}
