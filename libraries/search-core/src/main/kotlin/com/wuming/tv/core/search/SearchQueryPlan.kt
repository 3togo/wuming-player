package com.wuming.tv.core.search

class SearchQueryPlan(
    rawQuery: String?,
    canonicalKey: String?,
    variants: List<Variant>,
    identity: TitleIdentity?,
) {
    private val rawQuery = rawQuery?.trim().orEmpty()
    private val canonicalKey = canonicalKey.orEmpty()
    private val variants = variants.toList()
    private val identity = identity ?: TitleIdentity.empty()

    fun rawQuery(): String = rawQuery

    fun canonicalKey(): String = canonicalKey

    fun variants(): List<Variant> = variants

    fun identity(): TitleIdentity = identity

    enum class Kind { ORIGINAL, SCRIPT_EQUIVALENT, KNOWN_ALIAS, SCRIPT_SEGMENT }

    class Variant(private val text: String, private val kind: Kind) {
        fun text(): String = text
        fun kind(): Kind = kind
    }
}
