package com.wuming.tv.core.search

class SearchQueryPlanner @JvmOverloads constructor(
    resolver: TitleAliasResolver?,
    private val normalizer: TitleNormalizer = TitleNormalizer.identity(),
) {
    private val resolver = resolver ?: TitleAliasResolver { TitleIdentity.empty() }

    fun plan(query: String?): SearchQueryPlan {
        val raw = query?.trim().orEmpty()
        val identity = resolver.resolve(raw) ?: TitleIdentity.empty()
        val variants = linkedMapOf<String, SearchQueryPlan.Variant>()
        add(variants, raw, SearchQueryPlan.Kind.ORIGINAL)

        val simplified = normalizer.simplified(raw)
        if (normalizer.containsHan(raw) && simplified != raw) {
            add(variants, simplified, SearchQueryPlan.Kind.SCRIPT_EQUIVALENT)
        }

        for (alias in identity.aliases()) {
            val simplifiedAlias = normalizer.simplified(alias)
            if (normalizer.containsHan(simplifiedAlias)) {
                add(variants, simplifiedAlias, SearchQueryPlan.Kind.KNOWN_ALIAS)
            }
        }

        val bounded = variants.values.take(MAX_VARIANTS)
        val canonicalKey = if (identity.canonicalId().isNotEmpty()) {
            "id:${identity.canonicalId()}"
        } else {
            normalizer.key(raw)
        }
        return SearchQueryPlan(raw, canonicalKey, bounded, identity)
    }

    private fun add(
        variants: MutableMap<String, SearchQueryPlan.Variant>,
        text: String?,
        kind: SearchQueryPlan.Kind,
    ) {
        if (text.isNullOrBlank()) return
        val key = normalizer.clean(text)
        if (key.isEmpty()) return
        variants.putIfAbsent(key, SearchQueryPlan.Variant(text.trim(), kind))
    }

    companion object {
        const val MAX_VARIANTS = 3

        @JvmStatic
        @JvmOverloads
        fun localOnly(normalizer: TitleNormalizer = TitleNormalizer.identity()): SearchQueryPlanner =
            SearchQueryPlanner(TitleAliasResolver { TitleIdentity.empty() }, normalizer)
    }
}
