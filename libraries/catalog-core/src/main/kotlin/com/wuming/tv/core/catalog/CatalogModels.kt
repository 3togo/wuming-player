package com.wuming.tv.core.catalog

data class CatalogSource(
    val key: String = "",
    val name: String = "",
    val searchable: Boolean = true,
    /** Android `indexs`: cards from this discovery source open an all-site title search. */
    val indexProvider: Boolean = false,
) {
    /** Preserves the original Java-facing constructor. */
    constructor(key: String, name: String) : this(key, name, true, false)
}

data class CatalogItem(
    val sourceKey: String = "",
    override val id: String = "",
    override val name: String = "",
    val picture: String = "",
    override val remarks: String = "",
    override val year: String = "",
    val kind: CatalogItemKind = CatalogItemKind.MEDIA,
    val action: String = "",
    override val typeName: String = "",
    override val area: String = "",
    override val director: String = "",
    override val actor: String = "",
    override val content: String = "",
) : RecommendationCandidate, VodView {
    /** Preserves the original Java-facing constructor used by the Android desktop bridge. */
    constructor(
        sourceKey: String,
        id: String,
        name: String,
        picture: String,
        remarks: String,
        year: String,
    ) : this(sourceKey, id, name, picture, remarks, year, CatalogItemKind.MEDIA, "")

    override val itemId: String get() = id
    // VodView: the list card's poster is exposed as the canonical `pic` field.
    override val pic: String get() = picture
}

enum class CatalogItemKind { MEDIA, FOLDER, ACTION }

data class CatalogFilterValue(val name: String = "", val value: String = "")

data class CatalogFilter(
    val key: String = "",
    val name: String = "",
    /** Null means no initial selection; an explicit empty string remains a provider value. */
    val initialValue: String? = null,
    val values: List<CatalogFilterValue> = emptyList(),
)

/** Platform-neutral view of one Leanback filter row and its active provider value. */
data class CatalogFilterRow(
    val key: String = "",
    val name: String = "",
    val values: List<CatalogFilterValue> = emptyList(),
    val selectedValue: String? = null,
)

data class CatalogType(
    val id: String = "",
    val name: String = "",
    val folder: Boolean = false,
    val filters: List<CatalogFilter> = emptyList(),
)

data class CatalogHome(
    val items: List<CatalogItem> = emptyList(),
    val types: List<CatalogType> = emptyList(),
)

/** Mirrors Android SiteApi: configured category names restrict and order provider media types. */
object CatalogTypeSelectionPolicy {
    @JvmStatic
    fun orderNames(availableNames: List<String>, configuredNames: List<String>): List<String> {
        if (configuredNames.isEmpty()) return availableNames
        val available = availableNames.toSet()
        return configuredNames.filter(available::contains).distinct().ifEmpty { availableNames }
    }

    @JvmStatic
    fun select(types: List<CatalogType>, configuredNames: List<String>): List<CatalogType> {
        val byName = types.associateBy(CatalogType::name)
        return orderNames(types.map(CatalogType::name), configuredNames)
            .mapNotNull(byName::get)
            .distinctBy(CatalogType::id)
    }
}

fun CatalogHome.withConfiguredCategories(names: List<String>): CatalogHome =
    copy(types = CatalogTypeSelectionPolicy.select(types, names))

data class CatalogPage(
    val items: List<CatalogItem> = emptyList(),
    val page: Int = 1,
    /** Zero is the CatVod/Leanback sentinel for an unknown page count. */
    val pageCount: Int = 0,
    val total: Int = items.size,
) {
    val hasPrevious: Boolean get() = page > 1
    val hasNext: Boolean get() = pageCount == 0 || page < pageCount
}

/** Leanback-compatible defaults and validation for provider-defined category filters. */
object CatalogFilterSelectionPolicy {
    @JvmStatic
    fun defaults(type: CatalogType): Map<String, String> = type.filters.mapNotNull { filter ->
        filter.initialValue?.takeIf { initial -> filter.values.any { it.value == initial } }
            ?.let { filter.key to it }
    }.toMap()

    @JvmStatic
    fun normalize(type: CatalogType, selected: Map<String, String>): Map<String, String> =
        type.filters.mapNotNull { filter ->
            selected[filter.key]?.takeIf { value -> filter.values.any { it.value == value } }
                ?.let { filter.key to it }
        }.toMap()

    /**
     * Mirrors Leanback Value.setActivated: selecting a different value replaces the row's
     * selection, while selecting the active value again removes that filter entirely.
     */
    @JvmStatic
    fun toggle(
        type: CatalogType,
        selected: Map<String, String>,
        key: String,
        value: String,
    ): Map<String, String> {
        val normalized = normalize(type, selected).toMutableMap()
        val filter = type.filters.firstOrNull { it.key == key } ?: return normalized
        if (filter.values.none { it.value == value }) return normalized
        if (normalized[key] == value) normalized.remove(key) else normalized[key] = value
        return normalize(type, normalized)
    }

    /** Keeps provider row/value ordering so every platform can render the same filter model. */
    @JvmStatic
    fun rows(type: CatalogType, selected: Map<String, String>): List<CatalogFilterRow> {
        val normalized = normalize(type, selected)
        return type.filters.map { filter ->
            CatalogFilterRow(filter.key, filter.name, filter.values, normalized[filter.key])
        }
    }
}

/** Retains independent Leanback-style selections while a user moves between provider types. */
class CatalogFilterSelectionSession {
    private val selections = linkedMapOf<CatalogFilterScope, Map<String, String>>()

    fun selection(sourceKey: String, type: CatalogType): Map<String, String> {
        val scope = CatalogFilterScope(sourceKey, type.id)
        val selected = selections[scope]?.let { CatalogFilterSelectionPolicy.normalize(type, it) }
            ?: CatalogFilterSelectionPolicy.defaults(type)
        selections[scope] = selected
        return selected
    }

    fun toggle(
        sourceKey: String,
        type: CatalogType,
        key: String,
        value: String,
    ): Map<String, String> = CatalogFilterSelectionPolicy.toggle(
        type,
        selection(sourceKey, type),
        key,
        value,
    ).also { selections[CatalogFilterScope(sourceKey, type.id)] = it }

    fun reset(sourceKey: String, type: CatalogType): Map<String, String> =
        CatalogFilterSelectionPolicy.defaults(type).also {
            selections[CatalogFilterScope(sourceKey, type.id)] = it
        }

    fun remember(sourceKey: String, type: CatalogType, selected: Map<String, String>) {
        selections[CatalogFilterScope(sourceKey, type.id)] = CatalogFilterSelectionPolicy.normalize(type, selected)
    }

    fun clear() = selections.clear()
}

private data class CatalogFilterScope(val sourceKey: String, val typeId: String)

data class CatalogEpisode(
    val name: String = "",
    val id: String = "",
)

data class CatalogLine(
    val flag: String = "",
    val name: String = "",
    val episodes: List<CatalogEpisode> = emptyList(),
)

data class CatalogDetail(
    val item: CatalogItem = CatalogItem(),
    val content: String = "",
    val actor: String = "",
    val director: String = "",
    val area: String = "",
    val lines: List<CatalogLine> = emptyList(),
)

/** Retains list-card metadata that a provider legitimately omits from its detail response. */
fun CatalogDetail.withSummaryFallback(summary: CatalogItem): CatalogDetail = copy(
    item = item.copy(
        sourceKey = item.sourceKey.ifBlank { summary.sourceKey },
        id = item.id.ifBlank { summary.id },
        name = item.name.ifBlank { summary.name },
        picture = item.picture.ifBlank { summary.picture },
        remarks = item.remarks.ifBlank { summary.remarks },
        year = item.year.ifBlank { summary.year },
    ),
)

data class CatalogPlayback(
    val supported: Boolean = false,
    val url: String = "",
    val format: String = "",
    val message: String = "",
    val headers: Map<String, String> = emptyMap(),
    /**
     * Set when the provider derives playback through an Android WebView that the desktop host cannot
     * run (its WebView shim fell back to the emulator signal). The desktop UI should route such a
     * source to an Android device/emulator rather than showing a generic failure.
     */
    val requiresEmulator: Boolean = false,
) {
    constructor(supported: Boolean, url: String, format: String, message: String) :
        this(supported, url, format, message, emptyMap())
}

data class CatalogError(val error: String = "Request failed")
