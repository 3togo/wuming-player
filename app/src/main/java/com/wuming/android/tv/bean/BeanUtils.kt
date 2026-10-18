package com.wuming.android.tv.bean

import com.wuming.tv.core.base.BeanValues
import java.util.function.Function

/** Caller compatibility only; null/default semantics are shared. */
object BeanUtils {
    @JvmStatic fun isEmpty(value: String?): Boolean = BeanValues.isEmpty(value)
    @JvmStatic fun isNotEmpty(value: String?): Boolean = BeanValues.isNotEmpty(value)
    @JvmStatic fun emptyIfNull(value: String?): String = BeanValues.emptyIfNull(value)
    @JvmStatic fun trimIfEmpty(value: String?): String = BeanValues.trimIfEmpty(value)
    @JvmStatic fun <T> emptyListIfNull(list: List<T>?): List<T> = BeanValues.emptyListIfNull(list)
    @JvmStatic fun <T> emptyListIfNull(list: List<T>?, createNew: Boolean): List<T> = BeanValues.emptyListIfNull(list, createNew)
    @JvmStatic fun <K, V> emptyMapIfNull(map: Map<K, V>?): Map<K, V> = BeanValues.emptyMapIfNull(map)
    @JvmStatic fun getOrDefault(value: Int?, defaultValue: Int): Int = BeanValues.getOrDefault(value, defaultValue)
    @JvmStatic fun getOrDefault(value: Float?, defaultValue: Float): Float = BeanValues.getOrDefault(value, defaultValue)
    @JvmStatic fun isEnabled(value: Int?): Boolean = BeanValues.isEnabled(value)
    @JvmStatic fun toInt(enabled: Boolean): Int = BeanValues.toInt(enabled)
    @JvmStatic fun toMillisTimeout(seconds: Int?, defaultTimeout: Long): Long = BeanValues.toMillisTimeout(seconds, defaultTimeout)
    @JvmStatic fun emptyOrTransform(value: String?, transformer: Function<String, String>): String = BeanValues.emptyOrTransform(value, transformer)
}
