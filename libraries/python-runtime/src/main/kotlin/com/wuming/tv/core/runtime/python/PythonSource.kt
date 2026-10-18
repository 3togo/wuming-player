package com.wuming.tv.core.runtime.python

import com.google.gson.JsonElement
import com.wuming.tv.core.catalog.*
import java.io.File
import java.security.MessageDigest

data class PythonSourceSpec(
    val key: String, val name: String, val api: String, val extension: String = "",
    val providerKey: String = key, val categories: List<String> = emptyList(),
    val storageIdentity: String = key,
)

/** Hosts own transport, runtime extraction and endpoint lifecycle. */
class PythonProvider(
    private val spec: PythonSourceSpec,
    private val root: File,
    private val startRuntime: () -> Unit,
    private val load: (String) -> ByteArray,
    private val host: Map<String, String> = emptyMap(),
) : AutoCloseable {
    private var handle: String? = null
    private var closed = false

    @Synchronized fun call(method: String, vararg args: Any?): JsonElement {
        check(!closed) { "Python provider is closed" }
        if (handle == null) {
            startRuntime()
            val source = load(spec.api)
            require(source.size <= 4 * 1024 * 1024) { "Python source exceeds 4 MiB" }
            val id = MessageDigest.getInstance("SHA-256").digest(spec.storageIdentity.toByteArray()).joinToString("") { "%02x".format(it) }
            val directory = File(root, id).apply { mkdirs() }
            handle = NativePythonBridge.request(mapOf("op" to "open", "api" to spec.api,
                "source" to source.toString(Charsets.UTF_8).removePrefix("\uFEFF"), "directory" to directory.absolutePath,
                "providerKey" to spec.providerKey, "extension" to spec.extension, "host" to host)).asString
        }
        return NativePythonBridge.request(mapOf("op" to "call", "handle" to handle, "method" to method, "args" to args))
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        val current = handle
        handle = null
        if (current != null) NativePythonBridge.request(mapOf("op" to "close", "handle" to current))
    }
}

class PythonCatalogSourceExecutor(
    private val spec: PythonSourceSpec, private val provider: PythonProvider,
    private val onClose: () -> Unit = {},
) : CatalogSourceExecutor, AutoCloseable {
    override val source = CatalogSource(spec.key, spec.name)
    private val codec = CatVodCatalogCodec(source)
    override fun home(): List<CatalogItem> = browseHome().items
    override fun browseHome(): CatalogHome {
        val primary = codec.home(provider.call("homeContent", true).toString()).withConfiguredCategories(spec.categories)
        val featured = codec.home(provider.call("homeVideoContent").toString()).items
        return primary.copy(items = (primary.items + featured).distinctBy { it.id })
    }
    override fun category(typeId: String, page: Int, filters: Map<String, String>): CatalogPage =
        codec.category(provider.call("categoryContent", typeId, page.toString(), true, filters).toString(), page)
    override fun search(query: String): List<CatalogItem> = codec.search(provider.call("searchContent", query, false, "1").toString())
    override fun detail(id: String): CatalogDetail = codec.detail(provider.call("detailContent", listOf(id)).toString(), id)
    override fun play(flag: String, id: String): CatalogPlayback = codec.playback(provider.call("playerContent", flag, id, emptyList<String>()).toString())
    override fun close() { try { provider.close() } finally { onClose() } }
}
