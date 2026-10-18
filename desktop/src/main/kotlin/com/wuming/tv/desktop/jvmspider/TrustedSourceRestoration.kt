package com.wuming.tv.desktop.jvmspider

import com.wuming.tv.core.catalog.CatalogSourceExecutor
import com.wuming.tv.core.runtime.jvmspider.JvmSpiderSiteSpec

/** One stale approval must not retire unrelated, successfully restored desktop hosts. */
internal fun restoreTrustedSources(
    trusted: List<Pair<JvmSpiderSiteSpec, GuardedSourceTrust>>,
    activate: (List<JvmSpiderSiteSpec>, GuardedSourceTrust) -> List<CatalogSourceExecutor>,
    onFailure: (List<JvmSpiderSiteSpec>, Exception) -> Unit,
    preferredKey: String? = null,
    // Successful callback transfers ownership to the UI, including on later cancellation.
    onReady: ((List<CatalogSourceExecutor>) -> Unit)? = null,
): List<CatalogSourceExecutor> {
    val activated = mutableListOf<CatalogSourceExecutor>()
    val transferred = mutableSetOf<CatalogSourceExecutor>()
    val preferred = trusted.filter { it.first.key == preferredKey }
    val remaining = trusted.filterNot { it.first.key == preferredKey }
    val groups = preferred.map { listOf(it) } + remaining.groupBy { (site, trust) -> site.artifactLocation to trust }.values
    try {
        groups.forEach { entries ->
            val specs = entries.map { it.first }
            val batch = try {
                activate(specs, entries.first().second)
            } catch (failure: Exception) {
                if (failure is InterruptedException || failure is java.util.concurrent.CancellationException) throw failure
                onFailure(specs, failure)
                emptyList()
            }
            activated += batch
            if (onReady != null && batch.isNotEmpty()) {
                onReady(batch)
                transferred.addAll(batch)
            }
        }
        return activated
    } catch (failure: Throwable) {
        activated.filterNot { it in transferred }.forEach { runCatching { (it as? AutoCloseable)?.close() } }
        throw failure
    }
}
