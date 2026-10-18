package com.wuming.tv.core.catalog

/**
 * One executable catalogue source, independent of JavaScript, JVM or remote transport.
 * Local runtime adapters implement this contract; presentation continues to use [CatalogGateway].
 */
interface CatalogSourceExecutor {

    val source: CatalogSource

    fun home(): List<CatalogItem> = emptyList()

    fun browseHome(): CatalogHome = CatalogHome(home())

    fun category(
        typeId: String,
        page: Int = 1,
        filters: Map<String, String> = emptyMap(),
    ): CatalogPage = CatalogPage()

    fun search(query: String): List<CatalogItem>

    fun detail(id: String): CatalogDetail

    fun play(flag: String, id: String): CatalogPlayback

    fun cancel() = Unit
}

/** Routes a collection of local source executors through the common catalogue gateway. */
class ExecutorCatalogGateway(executors: Collection<CatalogSourceExecutor>) : CatalogGateway {

    private val executors = executors.associateBy { it.source.key }.also {
        require(it.size == executors.size) { "Catalogue source executor keys must be unique" }
        require(it.keys.none(String::isBlank)) { "Catalogue source executor key is required" }
    }

    override fun sources(): List<CatalogSource> = executors.values.map { it.source }

    override fun home(source: String): List<CatalogItem> = executor(source).home()

    override fun browseHome(source: String): CatalogHome = executor(source).browseHome()

    override fun category(
        source: String,
        typeId: String,
        page: Int,
        filters: Map<String, String>,
    ): CatalogPage = executor(source).category(typeId, page, filters)

    override fun search(source: String, query: String): List<CatalogItem> =
        executor(source).search(query)

    override fun detail(source: String, id: String): CatalogDetail =
        executor(source).detail(id)

    override fun play(source: String, flag: String, id: String): CatalogPlayback =
        executor(source).play(flag, id)

    fun cancel(source: String) = executor(source).cancel()

    private fun executor(source: String): CatalogSourceExecutor =
        executors[source] ?: throw IllegalArgumentException("Unknown catalogue source")
}
