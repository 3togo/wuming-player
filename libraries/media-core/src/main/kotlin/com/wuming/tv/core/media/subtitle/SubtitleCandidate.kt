package com.wuming.tv.core.media.subtitle

import java.net.URI

class SubtitleCandidate(
    providerId: String?,
    id: String?,
    title: String?,
    language: String?,
    format: String?,
    downloadUri: URI?,
    archive: Boolean,
    matchScore: Int
) {
    val providerId: String = clean(providerId)
    val id: String = clean(id)
    val title: String = clean(title)
    val language: String = clean(language)
    val format: String = clean(format).lowercase(java.util.Locale.ROOT)
    val downloadUri: URI? = downloadUri
    val archive: Boolean = archive
    val matchScore: Int = matchScore.coerceIn(0, 100)

    init {
        require(this.providerId.isNotEmpty() && this.id.isNotEmpty()) {
            "Subtitle provider and candidate IDs are required"
        }
    }

    fun providerId(): String = providerId
    fun id(): String = id
    fun title(): String = title
    fun language(): String = language
    fun format(): String = format
    fun downloadUri(): URI? = downloadUri
    fun archive(): Boolean = archive
    fun matchScore(): Int = matchScore

    override fun equals(other: Any?): Boolean =
        other is SubtitleCandidate && providerId == other.providerId &&
        id == other.id &&
        title == other.title &&
        language == other.language &&
        format == other.format &&
        downloadUri == other.downloadUri &&
        archive == other.archive &&
        matchScore == other.matchScore

    override fun hashCode(): Int = listOf(providerId, id, title, language, format, downloadUri, archive, matchScore).hashCode()

    override fun toString(): String = "SubtitleCandidate(providerId=$providerId, id=$id, title=$title, language=$language, format=$format, downloadUri=$downloadUri, archive=$archive, matchScore=$matchScore)"

    companion object {
        private fun clean(value: String?): String = value?.trim() ?: ""
    }
}
