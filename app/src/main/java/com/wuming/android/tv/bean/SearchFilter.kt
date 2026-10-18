package com.wuming.android.tv.bean

import java.util.Locale

class SearchFilter {
    enum class Sort { RELEVANCE, NAME, YEAR }
    enum class Type { ALL, MOVIE, TV }

    var sort: Sort = Sort.RELEVANCE
    var type: Type = Type.ALL

    fun getSortLabel(): String = when (sort) {
        Sort.RELEVANCE -> "Relevance"
        Sort.NAME -> "Name"
        Sort.YEAR -> "Year"
    }

    fun getTypeLabel(): String = when (type) {
        Type.ALL -> "All"
        Type.MOVIE -> "Movie"
        Type.TV -> "TV"
    }

    fun matches(vod: Vod): Boolean {
        return com.wuming.tv.core.search.ResultCollections.matchesType(
            com.wuming.tv.core.search.ResultCollections.Type.valueOf(type.name), vod.typeName)
    }
}
