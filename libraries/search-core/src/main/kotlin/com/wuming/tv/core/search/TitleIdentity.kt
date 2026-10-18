package com.wuming.tv.core.search

class TitleIdentity(
    canonicalId: String?,
    aliases: List<String>?,
    year: String?,
    mediaType: String?,
) {
    private val canonicalId = canonicalId?.trim().orEmpty()
    private val aliases = aliases.orEmpty()
        .asSequence()
        .filterNotNull()
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .toList()
    private val year = year?.trim().orEmpty()
    private val mediaType = mediaType?.trim().orEmpty()

    fun canonicalId(): String = canonicalId

    fun aliases(): List<String> = aliases

    fun year(): String = year

    fun mediaType(): String = mediaType

    fun isEmpty(): Boolean = canonicalId.isEmpty() && aliases.isEmpty()

    fun matches(title: String?, normalizer: TitleNormalizer): Boolean =
        aliases.any { normalizer.equivalent(it, title) }

    fun withAlias(alias: String?): TitleIdentity =
        TitleIdentity(canonicalId, aliases + listOfNotNull(alias), year, mediaType)

    companion object {
        @JvmStatic
        fun empty(): TitleIdentity = TitleIdentity("", emptyList(), "", "")
    }
}
