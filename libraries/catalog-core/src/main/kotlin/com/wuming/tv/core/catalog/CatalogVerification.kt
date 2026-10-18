package com.wuming.tv.core.catalog

/** Evidence records successful responses, never an assumption based on a provider name. */
enum class CatalogCapability { HOME_TYPES, LISTING, CATEGORY, SEARCH, DETAIL, PLAYBACK_URL }

data class CatalogVerification(val observedAt: Map<CatalogCapability, Long> = emptyMap()) {
    fun fresh(now: Long): Set<CatalogCapability> = observedAt.filterValues {
        it <= now && now - it <= MAX_AGE_MILLIS
    }.keys

    fun catalogVerified(now: Long): Boolean = fresh(now).let {
        CatalogCapability.LISTING in it && CatalogCapability.DETAIL in it
    }

    fun discoveryVerified(now: Long): Boolean = fresh(now).let {
        CatalogCapability.HOME_TYPES in it && CatalogCapability.CATEGORY in it
    }

    companion object { const val MAX_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000 }
}

/** Observes normal requests without extra network calls or changing their results. */
class VerifyingCatalogSourceExecutor(
    private val delegate: CatalogSourceExecutor,
    private val record: (Set<CatalogCapability>) -> Unit,
    private val closeResource: () -> Unit = {},
) : CatalogSourceExecutor by delegate, AutoCloseable {
    private fun observed(vararg capabilities: CatalogCapability) {
        // Verification storage is auxiliary: a disk failure must not break a working provider.
        runCatching { record(capabilities.toSet()) }
    }

    override fun home(): List<CatalogItem> = browseHome().items

    override fun browseHome(): CatalogHome = delegate.browseHome().also {
        if (it.types.any { type -> type.id.isNotBlank() }) observed(CatalogCapability.HOME_TYPES)
        if (it.items.any(::media)) observed(CatalogCapability.LISTING)
    }

    override fun category(typeId: String, page: Int, filters: Map<String, String>): CatalogPage =
        delegate.category(typeId, page, filters).also {
            if (it.items.any { item -> item.id.isNotBlank() }) observed(CatalogCapability.CATEGORY)
            if (it.items.any(::media)) observed(CatalogCapability.LISTING)
        }

    override fun search(query: String): List<CatalogItem> = delegate.search(query).also {
        if (it.any(::media)) observed(CatalogCapability.LISTING, CatalogCapability.SEARCH)
    }

    override fun detail(id: String): CatalogDetail = delegate.detail(id).also {
        if (it.lines.any { line -> line.episodes.any { episode -> episode.id.isNotBlank() } }) {
            observed(CatalogCapability.DETAIL)
        }
    }

    override fun play(flag: String, id: String): CatalogPlayback = delegate.play(flag, id).also {
        if (it.supported && !it.requiresEmulator &&
            (it.url.startsWith("https://") || it.url.startsWith("http://"))) {
            observed(CatalogCapability.PLAYBACK_URL)
        }
    }

    override fun close() {
        try { (delegate as? AutoCloseable)?.close() } finally { closeResource() }
    }

    private fun media(item: CatalogItem) = item.kind == CatalogItemKind.MEDIA && item.id.isNotBlank()
}

/** Bounded read-only probe; playback is observed only when the user actually requests it. */
object CatalogVerificationProbe {
    fun check(provider: CatalogSourceExecutor, searchable: Boolean, indexProvider: Boolean, query: String): CatalogVerification {
        val observations = mutableMapOf<CatalogCapability, Long>()
        val executor = VerifyingCatalogSourceExecutor(provider, { capabilities ->
            capabilities.forEach { observations[it] = System.currentTimeMillis() }
        })
        fun <T> attempt(block: () -> T): T? {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            return try { block() } catch (failure: Exception) {
                if (failure is InterruptedException || Thread.currentThread().isInterrupted) throw InterruptedException()
                null
            }
        }
        val home = attempt { executor.browseHome() }
        val items = home?.items.orEmpty().toMutableList()
        home?.types?.take(2)?.forEach { type ->
            attempt { executor.category(type.id, 1, CatalogFilterSelectionPolicy.defaults(type)) }
                ?.items?.let(items::addAll)
        }
        if (searchable && query.isNotBlank()) attempt { executor.search(query) }?.let(items::addAll)
        if (!indexProvider) items.filter { it.kind == CatalogItemKind.MEDIA && it.id.isNotBlank() }
            .distinctBy { it.id }.take(3).forEach { item ->
                val detail = attempt { executor.detail(item.id) }
                if (detail?.lines?.any { line -> line.episodes.any { it.id.isNotBlank() } } == true) return CatalogVerification(observations)
            }
        return CatalogVerification(observations)
    }
}
