package com.wuming.tv.core.source

/** Read-only view of a client-owned content source configuration. */
interface SourceAccess<T> {
    fun key(source: T): String
    fun displayName(source: T): String
    fun api(source: T): String
    fun extension(source: T): String
    fun jar(source: T): String
    fun type(source: T): Int
    fun timeoutMillis(source: T): Long
    fun hidden(source: T): Boolean
    fun indexable(source: T): Boolean
    fun searchable(source: T): Boolean
    fun changeable(source: T): Boolean
    fun quickSearch(source: T): Boolean
    fun categories(source: T): List<String>
    fun headers(source: T): Map<String, String>
}
