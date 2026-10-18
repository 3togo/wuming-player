package com.wuming.tv.core.runtime.js

import com.wuming.tv.core.catalog.CatalogHome
import com.wuming.tv.core.catalog.CatalogDetail
import com.wuming.tv.core.catalog.CatalogItem
import com.wuming.tv.core.catalog.CatalogPlayback
import com.wuming.tv.core.catalog.CatalogPage
import com.wuming.tv.core.catalog.CatalogSource
import com.wuming.tv.core.catalog.CatalogSourceExecutor
import com.wuming.tv.core.catalog.CatVodCatalogCodec
import com.wuming.tv.core.catalog.withConfiguredCategories
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.EnvironmentAccess
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.io.IOAccess
import org.graalvm.polyglot.proxy.ProxyArray
import org.graalvm.polyglot.proxy.ProxyExecutable
import org.graalvm.polyglot.proxy.ProxyObject
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

/** JVM adapter for CatVod/DRPY JavaScript modules with an explicit, narrow host bridge. */
class GraalJsCatalogSourceExecutor(
    private val spec: JsSourceSpec,
    private val gson: Gson = Gson(),
    private val moduleLoader: ((String) -> ByteArray)? = null,
    private val localStorage: JsLocalStorage = InMemoryJsLocalStorage(),
    private val proxyUrlFactory: JsProxyUrlFactory? = null,
    private val onClose: () -> Unit = {},
) : CatalogSourceExecutor, AutoCloseable {

    override val source = CatalogSource(spec.key, spec.name)
    private val codec = CatVodCatalogCodec(source)
    private val state = AtomicReference<State?>()

    @Synchronized
    override fun home(): List<CatalogItem> = browseHome().items

    @Synchronized
    override fun browseHome(): CatalogHome {
        val primary = codec.home(call("home", true)).withConfiguredCategories(spec.categories)
        val featured = callOptional("homeVod")?.let(codec::home)?.items.orEmpty()
        return primary.copy(items = (primary.items + featured).distinctBy(CatalogItem::id))
    }

    @Synchronized
    override fun category(typeId: String, page: Int, filters: Map<String, String>): CatalogPage {
        require(typeId.isNotBlank()) { "Category type is required" }
        require(page >= 1) { "Category page must be positive" }
        return codec.category(
            call(
                "category",
                typeId,
                page.toString(),
                true,
                ProxyObject.fromMap(filters.mapValues { it.value as Any }.toMutableMap()),
            ),
            page,
        )
    }

    @Synchronized
    override fun search(query: String): List<CatalogItem> {
        require(query.isNotBlank()) { "Search query is required" }
        return codec.search(call("search", query, false, 1))
    }

    @Synchronized
    override fun detail(id: String): CatalogDetail {
        return codec.detail(call("detail", id), id)
    }

    @Synchronized
    override fun play(flag: String, id: String): CatalogPlayback {
        return codec.playback(call("play", flag, id, emptyList<String>()))
    }

    override fun cancel() = close()

    @Synchronized
    override fun close() {
        state.getAndSet(null)?.close()
        onClose()
    }

    /** Invoked only by a host-owned loopback server for URLs minted by this source. */
    @Synchronized
    fun proxy(
        segments: List<String>,
        headers: Map<String, String>,
        requestHeaders: Map<String, String> = emptyMap(),
    ): JsProxyResponse {
        require(segments.isNotEmpty()) { "JavaScript proxy path is empty" }
        val payload = call(
            "proxy",
            ProxyArray.fromList(segments),
            ProxyObject.fromMap(headers.mapValues { it.value as Any }.toMutableMap()),
            ProxyObject.fromMap(requestHeaders.mapValues { it.value as Any }.toMutableMap()),
        )
        val result = runCatching { JsonParser.parseString(payload).asJsonObject }
            .getOrElse { throw IllegalArgumentException("JavaScript proxy returned invalid JSON") }
        val status = runCatching { result.get("code")?.asInt }.getOrNull()?.coerceIn(100, 599) ?: 200
        val bufferMode = runCatching { result.get("buffer")?.asInt }.getOrNull() ?: 0
        require(bufferMode == 0 || bufferMode == 2) {
            "JavaScript proxy buffer mode $bufferMode requires an unsupported file or streaming host capability"
        }
        val encoded = bufferMode == 2
        val content = runCatching { result.get("content")?.asString }.getOrNull().orEmpty()
        val body = if (encoded) runCatching { Base64.getDecoder().decode(content) }
            .getOrElse { throw IllegalArgumentException("JavaScript proxy returned invalid base64") }
        else content.toByteArray(StandardCharsets.UTF_8)
        require(body.size <= MAX_PROXY_BODY_BYTES) { "JavaScript proxy response exceeds size limit" }
        val responseHeaders = result.getAsJsonObject("headers")?.entrySet().orEmpty()
            .mapNotNull { (name, value) ->
                name.takeIf { it.length <= 128 }?.let { it to value.asString.take(8_192) }
            }.toMap()
        return JsProxyResponse(status, body, responseHeaders)
    }

    private fun call(name: String, vararg arguments: Any): String {
        val runtime = state.updateAndGet { it ?: create() }!!
        val function = runtime.api.getMember(name)
        require(function != null && function.canExecute()) { "JavaScript source does not implement $name" }
        val value = settle(function.execute(*arguments))
        if (value.isString) return value.asString()
        if (value.isNull) return "{}"
        return runtime.stringify.execute(value).takeIf(Value::isString)?.asString()
            ?: throw IllegalArgumentException("Provider returned a value that cannot be serialized as JSON")
    }

    private fun callOptional(name: String, vararg arguments: Any): String? {
        val runtime = state.updateAndGet { it ?: create() }!!
        val function = runtime.api.getMember(name)
        return if (function != null && function.canExecute()) call(name, *arguments) else null
    }

    private fun settle(value: Value): Value {
        if (!value.hasMembers()) return value
        if (value.getMember("then")?.canExecute() != true) return value
        // CatVodOpen's async functions resolve while the bounded synchronous host call drains
        // GraalJS microtasks. Do not introduce an unbounded event-loop wait here.
        val resolved = AtomicReference<Value?>()
        val rejected = AtomicReference<String?>()
        value.invokeMember(
            "then",
            ProxyExecutable { args -> resolved.set(args.firstOrNull()); null },
            ProxyExecutable { args -> rejected.set(args.firstOrNull()?.toString().orEmpty()); null },
        )
        rejected.get()?.let { throw IllegalArgumentException("JavaScript provider promise rejected: $it") }
        return resolved.get() ?: throw IllegalStateException("JavaScript provider promise did not settle")
    }

    private fun create(): State {
        val bridge = JsHostBridge(gson, storage = localStorage)
        val bundle = JsModuleBundle.fetch(spec.moduleUrl, moduleLoader)
        var opened: Context? = null
        try {
            val context = Context.newBuilder("js")
                .allowHostAccess(HostAccess.NONE)
                .allowHostClassLookup { false }
                .allowCreateThread(false)
                .allowNativeAccess(false)
                .allowEnvironmentAccess(EnvironmentAccess.NONE)
                .allowIO(IOAccess.newBuilder().fileSystem(BundleFileSystem(bundle.directory)).build())
                .option("js.esm-eval-returns-exports", "true")
                .option("engine.WarnInterpreterOnly", "false")
                .out(OutputStream.nullOutputStream())
                .err(OutputStream.nullOutputStream())
                .build()
            opened = context
            val bindings = context.getBindings("js")
            bindings.putMember("__tvboxHttp", ProxyExecutable { args -> bridge.request(args[0].asString(), args[1].asString()) })
            bindings.putMember("__tvboxJoin", ProxyExecutable { args -> bridge.join(args[0].asString(), args[1].asString()) })
            bindings.putMember("__tvboxGet", ProxyExecutable { args -> bridge.get(args[0].asString(), args[1].asString()) })
            bindings.putMember("__tvboxSet", ProxyExecutable { args -> bridge.set(args[0].asString(), args[1].asString(), args[2].asString()) })
            bindings.putMember("__tvboxDelete", ProxyExecutable { args -> bridge.delete(args[0].asString(), args[1].asString()) })
            bindings.putMember("__tvboxProxy", ProxyExecutable { args ->
                proxyUrlFactory?.create(args[0].asString(), bridge.headers(args[1].asString())).orEmpty()
            })
            context.eval("js", BOOTSTRAP)
            val exports = context.eval(Source.newBuilder("js", bundle.entry.toFile())
                .mimeType("application/javascript+module").build())
            val catFactory = exports.getMember("__jsEvalReturn")?.takeIf(Value::canExecute)
            val exported = catFactory?.execute() ?: exports.getMember("default") ?: exports
            val api = if (exported.canExecute()) settle(exported.execute()) else exported
            require(api.hasMembers()) { "JavaScript module has no default source API" }
            val init = api.getMember("init")
            require(init != null && init.canExecute()) { "JavaScript source does not implement init" }
            val extension = runCatching { JsonParser.parseString(spec.extension) }.getOrNull()
                ?.takeIf { it.isJsonObject || it.isJsonArray }
                ?.let { context.eval("js", "JSON.parse(${gson.toJson(spec.extension)})") }
                ?: spec.extension
            val initArgument = if (catFactory == null) extension else ProxyObject.fromMap(mutableMapOf(
                "skey" to spec.providerKey,
                "stype" to 3,
                "ext" to extension,
            ))
            settle(init.execute(initArgument))
            return State(bundle, context, api, context.eval("js", "JSON.stringify"))
        } catch (error: Throwable) {
            // A Graal context holds native memory that no GC reclaims. Every failure path after
            // the context is built has to close it, or a source that fails to initialise leaks
            // one engine per attempt.
            runCatching { opened?.close(true) }
            bundle.close()
            throw error
        }
    }

    private data class State(
        val bundle: JsModuleBundle,
        val context: Context,
        val api: Value,
        val stringify: Value,
    ) : AutoCloseable {
        override fun close() {
            runCatching { context.close(true) }
            bundle.close()
        }
    }

    private companion object {
        const val MAX_PROXY_BODY_BYTES = 24 * 1024 * 1024
        val BOOTSTRAP = """
            globalThis._http = (url, options = {}) => JSON.parse(__tvboxHttp(String(url), JSON.stringify(options || {})));
            globalThis.req = globalThis._http;
            globalThis.http = globalThis._http;
            globalThis.joinUrl = (parent, child) => __tvboxJoin(String(parent || ''), String(child || ''));
            globalThis.local = {
              get: (space, key) => __tvboxGet(String(space || ''), String(key || '')),
              set: (space, key, value) => __tvboxSet(String(space || ''), String(key || ''), String(value || '')),
              delete: (space, key) => __tvboxDelete(String(space || ''), String(key || ''))
            };
            globalThis.getProxy = () => '';
            globalThis.js2Proxy = (dynamic, siteType, site, url, headers = {}) =>
              __tvboxProxy(String(url || ''), JSON.stringify(headers || {}));
            globalThis.s2t = value => String(value || '');
            globalThis.t2s = value => String(value || '');
        """.trimIndent()
    }
}
