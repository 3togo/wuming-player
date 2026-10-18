package com.wuming.android.tv.cloud.opendrive

import com.wuming.tv.core.cloud.CloudHandleVault

/** Android encrypted-storage bindings for shared provider-handle policy. */
internal object OpenDriveHandleVault {
    const val MAX_HANDLES = CloudHandleVault.MAX_HANDLES
    internal val shared = CloudHandleVault(object : CloudHandleVault.Store {
        override fun reference(provider: String, handle: String): String = OpenDriveTokenVault.reference(provider, handle)
        override fun put(reference: String, json: String) { OpenDriveTokenVault.put(reference, json) }
        override fun get(reference: String): String = OpenDriveTokenVault.get(reference)
        override fun remove(reference: String) { OpenDriveTokenVault.remove(reference) }
        override fun clearProvider(provider: String) { OpenDriveTokenVault.clearProvider(provider) }
    })
    @JvmStatic fun reference(providerId: String, handle: String): String = shared.reference(providerId, handle)
    @JvmStatic fun put(providerId: String, handle: String, value: Any) = shared.put(providerId, handle, value)
    @JvmStatic fun <T : Any> get(providerId: String, handle: String, type: Class<T>): T? = shared.get(providerId, handle, type)
    @JvmStatic fun clearProvider(providerId: String) = shared.clearProvider(providerId)
    @JvmStatic fun remove(reference: String) = shared.remove(reference)
}
