package com.wuming.tv.core.runtime.jvmspider

import com.wuming.tv.core.catalog.CatalogDetail
import com.wuming.tv.core.catalog.CatalogItem
import com.wuming.tv.core.catalog.CatalogHome
import com.wuming.tv.core.catalog.CatalogPage
import com.wuming.tv.core.catalog.CatalogPlayback
import com.wuming.tv.core.catalog.CatalogSource
import com.wuming.tv.core.catalog.CatalogSourceExecutor
import com.wuming.tv.core.catalog.CatVodCatalogCodec
import com.wuming.tv.core.catalog.withConfiguredCategories
import java.nio.file.Path

class JvmCatVodCatalogSourceExecutor(
    private val spec: JvmSpiderSiteSpec,
    artifact: Path,
    expectedSha256: String,
) : CatalogSourceExecutor, AutoCloseable {

    override val source = CatalogSource(spec.key, spec.name)
    val artifactReport = JvmSpiderArtifactInspector.inspect(artifact, expectedSha256)
    private val host = JvmSpiderHostProcess(artifact, spec.className, spec.extension, spec.providerKey)
    private val codec = CatVodCatalogCodec(source)

    override fun home(): List<CatalogItem> = browseHome().items

    override fun browseHome(): CatalogHome = codec.home(host.request(JvmSpiderHostRequest("home")))
        .withConfiguredCategories(spec.categories)

    override fun category(typeId: String, page: Int, filters: Map<String, String>): CatalogPage =
        codec.category(host.request(JvmSpiderHostRequest(
            operation = "category",
            id = typeId,
            page = page.toString(),
            filters = filters,
        )), page)

    override fun search(query: String): List<CatalogItem> {
        require(query.isNotBlank()) { "Search query is required" }
        return codec.search(host.request(JvmSpiderHostRequest("search", query = query)))
    }

    override fun detail(id: String): CatalogDetail {
        require(id.isNotBlank()) { "Detail ID is required" }
        return codec.detail(host.request(JvmSpiderHostRequest("detail", id = id)), id)
    }

    override fun play(flag: String, id: String): CatalogPlayback {
        require(id.isNotBlank()) { "Playback ID is required" }
        return codec.playback(host.request(JvmSpiderHostRequest("play", id = id, flag = flag)))
    }

    override fun close() {
        host.close()
    }

}
