package com.wuming.tv.core.search

fun interface TitleAliasResolver {
    fun resolve(query: String): TitleIdentity?
}
