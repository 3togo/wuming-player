package com.wuming.tv.core.runtime.js

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

class GraalJsCatalogSourceExecutorTest {
    private lateinit var server: HttpServer
    private lateinit var base: String

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/helper.js") { exchange ->
            respond(exchange, "export const title = 'Local result';")
        }
        server.createContext("/source.js") { exchange ->
            respond(exchange, """
                import { title } from './helper.js';
                let extension = '';
                export default {
                  init: value => { extension = value; },
                  home: filter => JSON.stringify({
                    class:[{type_id:'movie',type_name:'Movies'}],
                    filters:{movie:[{key:'year',name:'Year',init:'2026',value:[{n:'2026',v:'2026'}]}]},
                    list:[{vod_id:'home-1',vod_name:'Featured',vod_remarks:String(filter)}]
                  }),
                  category: (type, page, filter, extend) => JSON.stringify({
                    page:Number(page),pagecount:3,total:4,
                    list:[{vod_id:type + '-' + extend.year,vod_name:'Filtered ' + page}]
                  }),
                  search: query => JSON.stringify({list:[{vod_id:'id-1',vod_name:title,vod_year:extension,vod_remarks:query}]}),
                  detail: id => JSON.stringify({list:[{vod_id:id,vod_name:title,vod_play_from:'line',vod_play_url:'Episode 1${'$'}https://media.example/video.m3u8'}]}),
                  play: (flag, id) => JSON.stringify({url:id,format:'hls',header:{Referer:'https://example.test/'}})
                };
            """.trimIndent())
        }
        server.createContext("/escape.js") { exchange ->
            respond(exchange, "import 'file:///etc/passwd'; export default {};")
        }
        server.createContext("/catvod-open.js") { exchange ->
            respond(exchange, """
                let config = {};
                export function __jsEvalReturn() {
                  return {
                    init: async value => { config = value; },
                    home: async () => ({class:[{type_id:'movie',type_name:'Movies'}]}),
                    homeVod: async () => ({list:[{vod_id:'featured',vod_name:'Featured'}]}),
                    category: async (type, page) => ({page:Number(page),pagecount:2,list:[{vod_id:type + '-' + page,vod_name:'Category'}]}),
                    search: async query => ({list:[{vod_id:'found',vod_name:query,vod_remarks:config.skey + ':' + config.stype,vod_year:config.ext.token}]}),
                    detail: async id => ({list:[{vod_id:id,vod_name:'Detail',vod_play_from:'line',vod_play_url:'Episode${'$'}stream-id'}]}),
                    play: async () => ({parse:0,url:['HD','https://media.example/catvod.m3u8'],header:{Referer:'https://catvod.example/'}})
                  };
                }
            """.trimIndent())
        }
        server.createContext("/proxy-source.js") { exchange ->
            respond(exchange, """
                let proxyUrl = '';
                export function __jsEvalReturn() {
                  return {
                    init: () => { proxyUrl = js2Proxy(true, 3, 'proxy', 'hls/' + encodeURIComponent('https://media.example/a b.m3u8'), {'X-Source':'yes'}); },
                    play: () => ({parse:0,url:proxyUrl}),
                    proxy: (segments, headers, requestHeaders) => segments[0] === 'stream' ? ({buffer:3,content:{}}) : ({
                      code:206,
                      content:decodeURIComponent(segments[1]) + ':' + headers['X-Source'] + ':' + requestHeaders['Range'],
                      headers:{'Content-Type':'application/vnd.apple.mpegurl'}
                    })
                  };
                }
            """.trimIndent())
        }
        server.createContext("/storage-source.js") { exchange ->
            respond(exchange, """
                export default {
                  init: () => {},
                  home: () => ({list:[{vod_id:'state',vod_name:local.get('state','value')}]}),
                  search: query => { local.set('state','value',query); return {list:[]}; }
                };
            """.trimIndent())
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun stop() = server.stop(0)

    @Test
    fun executesModuleGraphThroughCatalogContract() {
        GraalJsCatalogSourceExecutor(JsSourceSpec("local", "Local", "$base/source.js", "ext-value")).use { source ->
            assertEquals("Featured", source.home().single().name)
            val home = source.browseHome()
            assertEquals("Movies", home.types.single().name)
            assertEquals("2026", home.types.single().filters.single().initialValue)
            val category = source.category("movie", 2, mapOf("year" to "2026"))
            assertEquals("movie-2026", category.items.single().id)
            assertEquals(2, category.page)
            assertTrue(category.hasNext)
            val item = source.search("probe").single()
            assertEquals("Local result", item.name)
            assertEquals("ext-value", item.year)
            assertEquals("probe", item.remarks)

            val detail = source.detail(item.id)
            assertEquals("Episode 1", detail.lines.single().episodes.single().name)
            val playback = source.play(detail.lines.single().flag, detail.lines.single().episodes.single().id)
            assertTrue(playback.supported)
            assertEquals("https://media.example/video.m3u8", playback.url)
            assertEquals("https://example.test/", playback.headers["Referer"])
        }
    }

    @Test
    fun resolvesBareSiblingImportsUsedByDrpyModules() {
        server.createContext("/lib/helper.js") { respond(it, "export default 'bare sibling';") }
        server.createContext("/lib/drpy-style.js") { exchange ->
            respond(exchange, """
                import helper from "helper.js";
                export default {
                  init: () => {},
                  home: () => JSON.stringify({ list: [{ vod_id: '1', vod_name: helper }] })
                };
            """.trimIndent())
        }
        GraalJsCatalogSourceExecutor(JsSourceSpec("drpy", "DRPY", "$base/lib/drpy-style.js", "")).use { source ->
            assertEquals("bare sibling", source.home().single().name)
        }
    }

    @Test
    fun supportsCatVodOpenEntryEnvelopeObjectResultsAndQualityArrays() {
        val spec = JsSourceSpec("catvod", "CatVod", "$base/catvod-open.js", "{\"token\":\"ext-value\"}")
        GraalJsCatalogSourceExecutor(spec).use { source ->
            val home = source.browseHome()
            assertEquals("Movies", home.types.single().name)
            assertEquals("Featured", home.items.single().name)
            assertEquals("movie-2", source.category("movie", 2).items.single().id)

            val found = source.search("Probe").single()
            assertEquals("Probe", found.name)
            assertEquals("catvod:3", found.remarks)
            assertEquals("ext-value", found.year)

            val detail = source.detail(found.id)
            val playback = source.play(detail.lines.single().flag, detail.lines.single().episodes.single().id)
            assertTrue(playback.supported)
            assertEquals("https://media.example/catvod.m3u8", playback.url)
            assertEquals("https://catvod.example/", playback.headers["Referer"])
        }
    }

    @Test
    fun hostNamespaceDoesNotChangeCatVodProviderKey() {
        val spec = JsSourceSpec("package-host-key", "Package", "$base/catvod-open.js", "{}", providerKey = "original")
        GraalJsCatalogSourceExecutor(spec).use { source ->
            val result = source.search("Probe").single()
            assertEquals("package-host-key", result.sourceKey)
            assertEquals("original:3", result.remarks)
        }
    }

    @Test
    fun mintsHostOwnedProxyUrlsAndExecutesProviderProxyResults() {
        var mintedPath = ""
        var mintedHeaders = emptyMap<String, String>()
        val factory = JsProxyUrlFactory { path, headers ->
            mintedPath = path
            mintedHeaders = headers
            "http://127.0.0.1:12345/capability"
        }
        GraalJsCatalogSourceExecutor(
            JsSourceSpec("proxy", "Proxy", "$base/proxy-source.js", ""),
            proxyUrlFactory = factory,
        ).use { source ->
            assertEquals("http://127.0.0.1:12345/capability", source.play("line", "id").url)
            assertEquals("hls/https%3A%2F%2Fmedia.example%2Fa%20b.m3u8", mintedPath)
            assertEquals("yes", mintedHeaders["X-Source"])
            val response = source.proxy(
                listOf("hls", "https%3A%2F%2Fmedia.example%2Fa%20b.m3u8"),
                mintedHeaders,
                mapOf("Range" to "bytes=0-9"),
            )
            assertEquals(206, response.status)
            assertEquals("https://media.example/a b.m3u8:yes:bytes=0-9", response.body.toString(Charsets.UTF_8))
            assertEquals("application/vnd.apple.mpegurl", response.headers["Content-Type"])
            val streamError = runCatching {
                source.proxy(listOf("stream"), emptyMap())
            }.exceptionOrNull()
            assertTrue(streamError?.message.orEmpty().contains("unsupported file or streaming"))
        }
    }

    @Test
    fun usesHostSuppliedStorageAcrossRuntimeRecreation() {
        val storage = InMemoryJsLocalStorage()
        val spec = JsSourceSpec("storage", "Storage", "$base/storage-source.js", "")
        GraalJsCatalogSourceExecutor(spec, localStorage = storage).use { it.search("persisted") }
        GraalJsCatalogSourceExecutor(spec, localStorage = storage).use {
            assertEquals("persisted", it.home().single().name)
        }
    }

    @Test
    fun rejectsNonHttpPlaybackAndRedactsExtension() {
        val spec = JsSourceSpec("local", "Local", "$base/source.js", "secret-token")
        assertFalse(spec.toString().contains("secret-token"))
        GraalJsCatalogSourceExecutor(spec).use { source ->
            assertFalse(source.play("line", "file:///etc/passwd").supported)
        }
    }

    @Test
    fun deniesModuleFilesystemEscape() {
        val error = runCatching {
            GraalJsCatalogSourceExecutor(JsSourceSpec("escape", "Escape", "$base/escape.js", "")).use {
                it.search("probe")
            }
        }.exceptionOrNull()
        assertTrue(error != null)
    }

    private fun respond(exchange: com.sun.net.httpserver.HttpExchange, content: String) {
        val bytes = content.toByteArray()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
