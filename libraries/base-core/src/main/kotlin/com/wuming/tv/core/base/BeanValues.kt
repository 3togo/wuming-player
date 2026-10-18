package com.wuming.tv.core.base

import java.util.concurrent.TimeUnit
import java.util.function.Function

object BeanValues {
    @JvmStatic
    fun isEmpty(value: String?): Boolean = value == null || value.isEmpty()

    @JvmStatic
    fun isNotEmpty(value: String?): Boolean = !isEmpty(value)

    @JvmStatic
    fun emptyIfNull(value: String?): String = if (isEmpty(value)) "" else value!!

    @JvmStatic
    fun trimIfEmpty(value: String?): String = if (isEmpty(value)) "" else value!!.trim()

    @JvmStatic
    fun <T> emptyListIfNull(list: List<T>?): List<T> {
        return list ?: emptyList()
    }

    @JvmStatic
    fun <T> emptyListIfNull(list: List<T>?, createNew: Boolean): List<T> {
        if (list == null) return if (createNew) ArrayList<T>() else emptyList()
        return list
    }

    @JvmStatic
    fun <K, V> emptyMapIfNull(map: Map<K, V>?): Map<K, V> {
        if (map == null) return emptyMap()
        return map
    }

    @JvmStatic
    fun getOrDefault(value: Int?, defaultValue: Int): Int = value ?: defaultValue

    @JvmStatic
    fun getOrDefault(value: Float?, defaultValue: Float): Float = value ?: defaultValue

    @JvmStatic
    fun toInt(enabled: Boolean): Int = if (enabled) 1 else 2

    @JvmStatic
    fun isEnabled(value: Int?): Boolean = getOrDefault(value, 0) == 1

    @JvmStatic
    fun toMillisTimeout(seconds: Int?, defaultTimeout: Long): Long {
        if (seconds == null) return defaultTimeout
        return TimeUnit.SECONDS.toMillis(maxOf(seconds.toLong(), 1L))
    }

    @JvmStatic
    fun emptyOrTransform(value: String?, transformer: Function<String, String>): String =
        if (isEmpty(value)) "" else transformer.apply(value!!)
}
