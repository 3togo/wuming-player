package com.wuming.tv.core.runtime.js

import java.util.concurrent.ConcurrentHashMap

/** Host-owned storage used by the CatVod `local` global. */
interface JsLocalStorage {
    fun get(namespace: String, key: String): String
    fun set(namespace: String, key: String, value: String)
    fun delete(namespace: String, key: String)
}

class InMemoryJsLocalStorage : JsLocalStorage {
    private val values = ConcurrentHashMap<String, String>()

    override fun get(namespace: String, key: String): String = values[id(namespace, key)].orEmpty()
    override fun set(namespace: String, key: String, value: String) { values[id(namespace, key)] = value }
    override fun delete(namespace: String, key: String) { values.remove(id(namespace, key)) }

    private fun id(namespace: String, key: String) = "$namespace\u0000$key"
}

fun interface JsProxyUrlFactory {
    fun create(path: String, headers: Map<String, String>): String
}

data class JsProxyResponse(
    val status: Int,
    val body: ByteArray,
    val headers: Map<String, String> = emptyMap(),
)
