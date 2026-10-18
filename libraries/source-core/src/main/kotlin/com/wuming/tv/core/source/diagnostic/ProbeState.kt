package com.wuming.tv.core.source.diagnostic

import java.util.concurrent.ConcurrentHashMap

class ProbeState {
    private val values = ConcurrentHashMap<String, Any>()

    fun put(
        key: String?,
        value: Any?,
    ) {
        if (key != null && value != null) values[key] = value
    }

    fun string(key: String): String = values[key]?.toString() ?: ""

    fun <T> get(
        key: String,
        type: Class<T>,
    ): T? {
        val value = values[key]
        return if (type.isInstance(value)) type.cast(value) else null
    }

    fun snapshot(): Map<String, Any> = java.util.Map.copyOf(values)
}
