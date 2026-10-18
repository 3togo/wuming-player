package com.wuming.tv.core.media.subtitle

class SubtitlePage(
    candidates: List<SubtitleCandidate>?,
    page: Int,
    totalPages: Int
) {
    val candidates: List<SubtitleCandidate> = java.util.List.copyOf(candidates ?: emptyList())
    val page: Int = maxOf(1, page)
    val totalPages: Int = maxOf(this.page, totalPages)

    fun candidates(): List<SubtitleCandidate> = candidates
    fun page(): Int = page
    fun totalPages(): Int = totalPages

    override fun equals(other: Any?): Boolean =
        other is SubtitlePage && candidates == other.candidates &&
        page == other.page &&
        totalPages == other.totalPages

    override fun hashCode(): Int = listOf(candidates, page, totalPages).hashCode()

    override fun toString(): String = "SubtitlePage(candidates=$candidates, page=$page, totalPages=$totalPages)"

    companion object {
        @JvmStatic
        fun empty(page: Int): SubtitlePage = SubtitlePage(emptyList(), page, page)
    }
}
