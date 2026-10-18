package com.wuming.tv.core.media.subtitle

class SubtitleSearchResult(
    providerId: String?,
    page: SubtitlePage?,
    errorCode: String?
) {
    val providerId: String = providerId ?: ""
    val page: SubtitlePage = page ?: SubtitlePage.empty(1)
    val errorCode: String = errorCode ?: ""

    fun providerId(): String = providerId
    fun page(): SubtitlePage = page
    fun errorCode(): String = errorCode
    fun successful(): Boolean = successful

    override fun equals(other: Any?): Boolean =
        other is SubtitleSearchResult && providerId == other.providerId &&
        page == other.page &&
        errorCode == other.errorCode

    override fun hashCode(): Int = listOf(providerId, page, errorCode).hashCode()

    override fun toString(): String = "SubtitleSearchResult(providerId=$providerId, page=$page, errorCode=$errorCode)"

    val successful: Boolean
        get() = errorCode.isEmpty()
}
