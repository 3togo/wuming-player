package com.wuming.tv.desktop.python

import com.google.gson.JsonParser
import com.wuming.tv.core.runtime.python.*
import com.wuming.tv.desktop.net.DesktopResourceLoader
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors

object DesktopPythonRuntime {
    private fun runtimeRoot(): File = System.getProperty("wuming.python.runtime")?.let(::File)
        ?: System.getenv("WUMING_PYTHON_RUNTIME")?.let(::File)
        ?: File(File(DesktopPythonRuntime::class.java.protectionDomain.codeSource.location.toURI()).parentFile, "python-runtime").takeIf { File(it, "runtime.json").isFile }
        ?: File("build/python-native/linux")
    fun available(): Boolean = File(runtimeRoot(), "runtime.json").isFile
    @Synchronized fun start() {
        val root = runtimeRoot()
        val info = JsonParser.parseString(File(root, "runtime.json").readText()).asJsonObject
        val home = File(root, info.get("home").asString)
        val version = info.get("python").asString
        NativePythonBridge.start(home, listOf(File(home, "bridge"), File(home, "lib/python$version/site-packages")), File(root, info.get("library").asString))
    }
    fun executor(spec: PythonSourceSpec, dataRoot: File? = null): PythonCatalogSourceExecutor {
        val proxy = ProxyEndpoint()
        val data = System.getenv("XDG_DATA_HOME")?.let(::File) ?: File(System.getProperty("user.home"), ".local/share")
        val provider = PythonProvider(spec, dataRoot ?: File(data, "wuming/python-providers"), ::start,
            DesktopResourceLoader()::load, mapOf("proxyUrl" to proxy.url))
        proxy.provider = provider
        return PythonCatalogSourceExecutor(spec, provider, proxy::close)
    }

    /** One unguessable, revocable loopback endpoint for each provider. */
    private class ProxyEndpoint : AutoCloseable {
        lateinit var provider: PythonProvider
        private val secret = UUID.randomUUID().toString()
        private val workers = Executors.newFixedThreadPool(2) { task -> Thread(task, "python-proxy").apply { isDaemon = true } }
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = workers
            createContext("/$secret") { exchange ->
                try {
                    require(exchange.requestURI.path == "/$secret")
                    require(exchange.requestMethod in setOf("GET", "HEAD"))
                    val query = exchange.requestURI.rawQuery.orEmpty()
                    require(query.length <= 32768)
                    val params = query.split('&').filter { it.isNotEmpty() }.associate {
                        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
                    }
                    val result = provider.call("localProxy", params).asJsonObject
                    val body = Base64.getDecoder().decode(result.get("body").asString)
                    val status = result.get("status").asInt
                    require(status in 200..599)
                    result.getAsJsonObject("headers").entrySet().forEach { (key, value) ->
                        if (key.lowercase() !in setOf("connection", "transfer-encoding", "content-length") && !key.contains('\r') && !key.contains('\n') && !value.asString.contains('\r') && !value.asString.contains('\n')) exchange.responseHeaders.set(key, value.asString)
                    }
                    exchange.responseHeaders.set("Content-Type", result.get("contentType").asString)
                    exchange.sendResponseHeaders(status, if (exchange.requestMethod == "HEAD") -1 else body.size.toLong())
                    if (exchange.requestMethod != "HEAD") exchange.responseBody.use { it.write(body) }
                } catch (_: Exception) { runCatching { exchange.sendResponseHeaders(502, -1) } }
                finally { exchange.close() }
            }
            val self = this
            Thread({ self.start() }, "python-proxy-start").apply { isDaemon = true; start(); join() }
        }
        val url get() = "http://127.0.0.1:${server.address.port}/$secret?do=py"
        override fun close() { server.stop(0); workers.shutdownNow() }
    }
}
