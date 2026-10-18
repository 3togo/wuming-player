package com.wuming.tv.core.search

/** Converts a title to the canonical script used for matching and cache keys. */
fun interface TitleScriptConverter {
    fun simplify(value: String): String
}
