package com.wuming.tv.core.media.subtitle

class SubtitleQuery(
    title: String?,
    season: Int?,
    episode: Int?,
    languages: List<String>?,
    page: Int,
    pageSize: Int
) {
    val title: String = title?.trim() ?: ""
    val season: Int? = season
    val episode: Int? = episode
    val languages: List<String> = java.util.List.copyOf(languages ?: emptyList())
    val page: Int = maxOf(1, page)
    val pageSize: Int = pageSize.coerceIn(1, 50)

    init {
        require(this.title.isNotEmpty()) { "Subtitle title is required" }
    }

    fun title(): String = title
    fun season(): Int? = season
    fun episode(): Int? = episode
    fun languages(): List<String> = languages
    fun page(): Int = page
    fun pageSize(): Int = pageSize

    override fun equals(other: Any?): Boolean =
        other is SubtitleQuery && title == other.title &&
        season == other.season &&
        episode == other.episode &&
        languages == other.languages &&
        page == other.page &&
        pageSize == other.pageSize

    override fun hashCode(): Int = listOf(title, season, episode, languages, page, pageSize).hashCode()

    override fun toString(): String = "SubtitleQuery(title=$title, season=$season, episode=$episode, languages=$languages, page=$page, pageSize=$pageSize)"

    companion object {
        @JvmStatic
        fun movie(title: String): SubtitleQuery = SubtitleQuery(title, null, null, emptyList(), 1, 20)
    }
}
