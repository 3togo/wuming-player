package com.wuming.tv.core.cloud

import com.google.gson.Gson
import com.wuming.tv.core.cache.BoundedReferenceCache

/** Sensitive provider-handle metadata cache; host storage owns encryption and keys. */
class CloudHandleVault(private val store: Store) {
    interface Store {
        fun reference(provider: String, handle: String): String
        fun put(reference: String, json: String)
        fun get(reference: String): String
        fun remove(reference: String)
        fun clearProvider(provider: String)
    }
    private val gson = Gson()
    private val memory = BoundedReferenceCache<String, Any>(2048)

    fun reference(provider: String, handle: String): String = store.reference(provider, handle)
    fun put(provider: String, handle: String, value: Any) {
        val reference = reference(provider, handle)
        memory.put(reference, value)
        store.put(reference, gson.toJson(value))
    }
    fun <T : Any> get(provider: String, handle: String, type: Class<T>): T? {
        val reference = reference(provider, handle)
        val cached = memory.get(reference)
        if (type.isInstance(cached)) return type.cast(cached)
        val json = store.get(reference)
        if (json.isBlank()) return null
        return try { gson.fromJson(json, type)?.also { memory.put(reference, it) } }
        catch (_: RuntimeException) { store.remove(reference); null }
    }
    fun clearProvider(provider: String) {
        memory.removeKeysIf { it.startsWith("vault:opendrive/$provider/") }
        store.clearProvider(provider)
    }
    fun remove(reference: String) {
        memory.remove(reference)
        store.remove(reference)
    }
    companion object { const val MAX_HANDLES = 8192 }
}
