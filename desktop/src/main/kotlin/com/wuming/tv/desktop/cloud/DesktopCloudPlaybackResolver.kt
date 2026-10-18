package com.wuming.tv.desktop.cloud

import com.wuming.tv.core.catalog.*
import com.wuming.tv.core.cloud.CloudDriveDetector
import com.wuming.tv.core.cloud.CloudDriveProviderKind
import com.wuming.tv.core.cloud.SavedCopyRegistry
import com.wuming.tv.core.runtime.cloudlogin.*
import java.util.concurrent.ConcurrentHashMap
import com.wuming.tv.desktop.catalog.CachingCatalogSourceExecutor

/** Desktop supplies credential storage and the saved-copy ledger; protocol policy lives in core. */
class DesktopCloudPlaybackResolver(
    credentials: CloudCredentialStore,
    transport: CloudLoginTransport = JdkCloudLoginTransport(),
    copies: SavedCopyRegistry = DesktopSavedCopyStore.openDefault(),
    pause: (Long) -> Unit = Thread::sleep,
) {
    private val resolver = QuarkPlaybackResolver(credentials, transport, copies, pause)
    fun reviewCopies() = resolver.reviewCopies()
    fun reconcileCopies() = resolver.reconcileCopies()
    fun cleanCopies(reviewed: List<com.wuming.tv.core.cloud.SavedCopyRecord>) = resolver.cleanCopies(reviewed)
    fun forgetCopies(reviewed: List<com.wuming.tv.core.cloud.SavedCopyRecord>) = resolver.forgetCopies(reviewed)
    fun resolve(provider: CloudDriveProviderKind, id: String): CatalogPlayback = resolver.resolve(provider, id).let {
        CatalogPlayback(supported = it.supported, url = it.url, message = it.message, headers = it.headers)
    }
}

/** Decorates providers without changing the guarded-host protocol or exposing account cookies to it. */
class CloudResolvingCatalogSourceExecutor(
    private val delegate: CatalogSourceExecutor,
    private val resolver: DesktopCloudPlaybackResolver,
) : CatalogSourceExecutor, AutoCloseable {
    override val source = delegate.source
    private val cloudEpisodes = ConcurrentHashMap<Pair<String, String>, CloudDriveProviderKind>()

    override fun search(query: String): List<CatalogItem> = delegate.search(query)
    override fun home(): List<CatalogItem> = delegate.home()
    override fun browseHome(): CatalogHome = delegate.browseHome()
    override fun category(typeId: String, page: Int, filters: Map<String, String>): CatalogPage =
        delegate.category(typeId, page, filters)

    override fun detail(id: String): CatalogDetail = delegate.detail(id).also { detail ->
        val detected = ConcurrentHashMap<Pair<String, String>, CloudDriveProviderKind>()
        detail.lines.forEach { line -> line.episodes.forEach { episode ->
            CloudDriveDetector.detect(line.flag, episode.id, line.name).takeIf { it != CloudDriveProviderKind.NONE }
                ?.let { detected[line.flag to episode.id] = it }
        } }
        cloudEpisodes.clear()
        cloudEpisodes.putAll(detected)
    }

    override fun play(flag: String, id: String): CatalogPlayback = cloudEpisodes[flag to id]
        ?.let { resolver.resolve(it, id) } ?: delegate.play(flag, id)

    internal fun clearMetadataCache() = clearCatalogCache(delegate)

    override fun cancel() {
        cloudEpisodes.clear()
        delegate.cancel()
    }

    override fun close() {
        cloudEpisodes.clear()
        (delegate as? AutoCloseable)?.close()
    }
}

/** Routing observes every detail result, including results returned by the metadata cache. */
internal fun cloudAwareExecutor(executor: CatalogSourceExecutor, resolver: DesktopCloudPlaybackResolver?): CatalogSourceExecutor =
    CachingCatalogSourceExecutor(executor).let { cached ->
        resolver?.let { CloudResolvingCatalogSourceExecutor(cached, it) } ?: cached
    }

/** Explicit refresh must reach the cache through the cloud-routing decorator. */
internal fun clearCatalogCache(executor: CatalogSourceExecutor) {
    when (executor) {
        is CloudResolvingCatalogSourceExecutor -> executor.clearMetadataCache()
        is CachingCatalogSourceExecutor -> executor.clear()
    }
}
