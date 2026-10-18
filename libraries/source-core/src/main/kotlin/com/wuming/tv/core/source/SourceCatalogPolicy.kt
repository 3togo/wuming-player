package com.wuming.tv.core.source

/** Stable catalogue rules shared by configuration consumers on every platform. */
class SourceCatalogPolicy<T>(private val access: SourceAccess<T>) {

    private val admission = RecordAdmission.of({ source: T -> access.key(source) })

    /** The catalogue's own admission rules, so a caller can log what a config actually held. */
    fun admit(name: String, sources: List<T>?): SectionAdmission<T> = admission.admit(name, sources)

    fun distinct(sources: List<T>?): List<T> = admission.admit("sources", sources).kept

    fun withRequiredSource(sources: List<T>?, required: T): List<T> {
        val requiredKey = access.key(required)
        return distinct(sources).filterNot { access.key(it) == requiredKey } + required
    }

    fun index(sources: List<T>?): Map<String, T> = distinct(sources).associateBy { access.key(it) }

    fun selectPreferred(sources: List<T>?, preferredKey: String?): T? {
        if (sources.isNullOrEmpty()) return null
        return sources.firstOrNull { access.key(it) == preferredKey } ?: sources.first()
    }

    fun searchable(sources: List<T>?, enabledKeys: Set<String>? = null): List<T> =
        sources.orEmpty().filter { source ->
            access.searchable(source) && (enabledKeys == null || access.key(source) in enabledKeys)
        }

    fun quickSearchable(sources: List<T>?, enabledKeys: Set<String>? = null): List<T> =
        searchable(sources, enabledKeys).filter(access::quickSearch)
}
