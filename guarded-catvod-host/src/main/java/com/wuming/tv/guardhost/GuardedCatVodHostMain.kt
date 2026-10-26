package com.wuming.tv.guardhost

import android.app.Application
import android.content.Context
import com.github.catvod.crawler.Spider
import com.github.catvod.spider.HideUtils
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wuming.tv.core.runtime.guardspider.GuardedSpiderProxyServer
import com.wuming.tv.core.runtime.guardspider.SpiderInvocationSupport
import com.wuming.tv.guardhost.GuardedCatVodHostMain.value
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintStream
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.URL
import com.wuming.tv.core.runtime.guardspider.GuardedProviderClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.HashMap

/** Line-framed child-process host. Provider stdout is redirected away from the RPC stream.  */
object GuardedCatVodHostMain {
    private val GSON: Gson = Gson()

    @Throws(Exception::class)
    @JvmStatic
    fun main(args: Array<String?>) {
        val rpcOut: PrintStream = System.out
        System.setOut(PrintStream(System.err, true, StandardCharsets.UTF_8))
        if (args.size != 4 && args.size != 7) {
            rpcOut.println(
                GSON.toJson(
                    Response.Companion.error(
                        "Guarded host requires artifact, class, source key, data directory, and optional native assets"
                    )
                )
            )
            return
        }
        val artifact: Path = Path.of(args[0]).toAbsolutePath().normalize()
        val dataDirectory: Path = Path.of(args[3]).toAbsolutePath().normalize()
        if (!Files.isRegularFile(artifact)) {
            rpcOut.println(
                GSON.toJson(Response.Companion.error("Prepared guarded artifact is missing"))
            )
            return
        }
        Files.createDirectories(dataDirectory)
        if (args.size ==
            7
        ) {
            HideUtils.configure(args[4]!!, args[5], args[6], dataDirectory.toString())
        }
        GuardedProviderClassLoader(
            arrayOf<URL?>(artifact.toUri().toURL()),
            GuardedCatVodHostMain::class.java.getClassLoader()
        ).use { loader ->
            Thread.currentThread().setContextClassLoader(loader)
            val spider: Any =
                Class.forName(args[1], true, loader).getDeclaredConstructor().newInstance()
            SpiderInvocationSupport.setSiteKey(spider, args[2]!!)
            val context: Context = Application(dataDirectory.toFile())
            initializeOrigin(loader, context)
            try {
                GuardedSpiderProxyServer(
                    GuardedSpiderProxyServer.Handler { params: MutableMap<String?, String?>? ->
                        proxy(
                            loader,
                            spider,
                            params
                        )
                    }
                ).use { proxy ->
                    BufferedReader(
                        InputStreamReader(
                            System.`in`,
                            StandardCharsets.UTF_8
                        )
                    ).use { input ->
                        var line: String? = null
                        while ((input.readLine().also { line = it }) != null) {
                            val request: Request? =
                                GSON.fromJson<Request>(line, Request::class.java)
                            var response: Response?
                            try {
                                val result: String? = invoke(spider, context, request!!)
                                response = Response.Companion.ok(
                                    if ("play".equals(request.operation)) {
                                        proxy.rewritePlaybackJson(
                                            result
                                        )
                                    } else {
                                        result
                                    }
                                )
                            } catch (failure: Throwable) {
                                val cause: Throwable = rootCause(failure)
                                response = Response.Companion.error(
                                    cause.javaClass
                                        .getSimpleName() + ": " +
                                        java.lang.String.valueOf(cause.message)
                                )
                            }
                            rpcOut.println(GSON.toJson(response))
                            rpcOut.flush()
                            if ("close".equals(request!!.operation)) return
                        }
                    }
                }
            } finally {
                destroy(spider)
                HideUtils.close()
            }
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun proxy(
        loader: ClassLoader?,
        spider: Any?,
        params: MutableMap<String?, String?>?
    ): Array<Any?>? {
        for (name: String? in java.util.List.of(
            "com.github.catvod.spider.Proxy",
            "com.github.catvod.spider.ProxyOrigin"
        )) {
            try {
                val type: Class<*> = Class.forName(name, true, loader)
                val method: Method = type.getMethod("proxy", MutableMap::class.java)
                if (Modifier.isStatic(method.getModifiers())) {
                    return method.invoke(
                        null,
                        params
                    ) as Array<Any?>?
                }
            } catch (ignored: ClassNotFoundException) {
                // The prepared guarded bundle exports ProxyOrigin; ordinary bundles export Proxy.
            } catch (ignored: NoSuchMethodException) {
            }
        }
        val method: Method? = find(spider, "proxy", MutableMap::class.java)
        return if (method == null) null else method.invoke(spider, params) as Array<Any?>?
    }

    @Throws(Exception::class)
    private fun invoke(spider: Any?, context: Context?, request: Request): String? =
        when (request.operation) {
            "init" -> {
                val method: Method? = find(spider, "init", Context::class.java, String::class.java)
                if (method != null) {
                    method.invoke(
                        spider,
                        context,
                        GuardedCatVodHostMain.value(request.extension)
                    )
                } else {
                    required(spider, "init", Context::class.java).invoke(spider, context)
                }
                "{}"
            }

            "home" -> value(
                required(
                    spider,
                    "homeContent",
                    Boolean::class.javaPrimitiveType!!
                ).invoke(spider, true)
            )

            "homeVideo" -> value(required(spider, "homeVideoContent").invoke(spider))

            "category" -> value(
                required(
                    spider,
                    "categoryContent",
                    String::class.java,
                    String::class.java,
                    Boolean::class.javaPrimitiveType!!,
                    HashMap::class.java
                )
                    .invoke(
                        spider,
                        GuardedCatVodHostMain.value(request.id),
                        value(request.page, "1"),
                        true,
                        HashMap(
                            if (request.filters ==
                                null
                            ) {
                                java.util.Map.of()
                            } else {
                                request.filters
                            }
                        )
                    )
            )

            "search" -> value(
                required(
                    spider,
                    "searchContent",
                    String::class.java,
                    Boolean::class.javaPrimitiveType!!
                )
                    .invoke(spider, GuardedCatVodHostMain.value(request.query), false)
            )

            "detail" -> value(
                required(spider, "detailContent", List::class.java)
                    .invoke(spider, java.util.List.of(GuardedCatVodHostMain.value(request.id)))
            )

            "play" -> {
                var target: Any? = findCloudPlayer(spider, request.flag, request.id)
                if (target == null) target = spider
                val result: String = value(
                    required(
                        target,
                        "playerContent",
                        String::class.java,
                        String::class.java,
                        List::class.java
                    )
                        .invoke(
                            target,
                            GuardedCatVodHostMain.value(request.flag),
                            GuardedCatVodHostMain.value(request.id),
                            java.util.List.of<String>()
                        )
                )
                attachPlaybackCookies(result)
            }

            "close" -> "{}"

            else -> throw IllegalArgumentException("Unsupported guarded host operation")
        }

    /**
     * Leanback resolves and plays a provider URL through one shared CookieJar. The desktop host is
     * intentionally isolated, so copy only cookies which OkHttp would send to the resolved media
     * URL into CatVod's existing header field before returning across the process boundary.
     */
    @JvmStatic
    fun attachPlaybackCookies(result: String): String =
        SpiderInvocationSupport.attachPlaybackCookies(result) { url: String? ->
            Spider.cookieHeader(url)
        }

    @Throws(Exception::class)
    private fun initializeOrigin(loader: ClassLoader?, context: Context?) {
        try {
            val type: Class<*> = Class.forName("com.github.catvod.spider.InitOrigin", true, loader)
            val singleton: Any? = type.getMethod("get").invoke(null)
            val nativeBridge: Method? = findStatic(type, "setClass", Class::class.java)
            if (nativeBridge != null && HideUtils.isConfigured) {
                nativeBridge.invoke(
                    null,
                    HideUtils::class.java
                )
            }
            for (field: Field in type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) &&
                    Application::class.java.isAssignableFrom(
                        field.getType()
                    )
                ) {
                    field.setAccessible(true)
                    field.set(singleton, context)
                    return
                }
            }
        } catch (ignored: ClassNotFoundException) {
            // Providers which do not contain the guarded bootstrap need no global context.
        }
    }

    private fun findStatic(type: Class<*>, name: String, vararg parameters: Class<*>): Method? =
        SpiderInvocationSupport.findStatic(type, name, *parameters)

    private fun required(spider: Any?, name: String, vararg parameters: Class<*>): Method =
        SpiderInvocationSupport.required(spider!!, name, *parameters)

    /** Route cloud-drive episodes around Pan's Android-only Activity decoration wrapper.  */
    private fun findCloudPlayer(spider: Any?, flag: String?, address: String?): Any? =
        SpiderInvocationSupport.findCloudPlayer(spider!!, flag, address)

    @JvmStatic
    fun cloudPlayerField(value: String): String? = SpiderInvocationSupport.cloudPlayerField(value)

    private fun findField(type: Class<*>, name: String): Field? =
        SpiderInvocationSupport.findField(type, name)

    private fun find(spider: Any?, name: String, vararg parameters: Class<*>): Method? =
        SpiderInvocationSupport.find(spider!!, name, *parameters)

    private fun destroy(spider: Any) {
        val method: Method? = find(spider, "destroy")
        if (method != null) {
            try {
                method.invoke(spider)
            } catch (ignored: ReflectiveOperationException) {
            }
        }
    }

    private fun rootCause(failure: Throwable): Throwable =
        SpiderInvocationSupport.rootCause(failure)

    private fun value(value: Any?): String = SpiderInvocationSupport.value(value)

    private fun value(value: String?): String = SpiderInvocationSupport.value(value)

    private fun value(value: String?, fallback: String): String =
        SpiderInvocationSupport.value(value, fallback)

    private class Request {
        var operation: String? = null
        var query: String? = null
        var id: String? = null
        var flag: String? = null
        var page: String? = null
        var extension: String? = null
        var filters: MutableMap<String?, String?>? = null
    }

    @JvmRecord
    private data class Response(val ok: Boolean, val result: String?, val error: String?) {
        companion object {
            @JvmStatic
            fun ok(result: String?): Response = Response(true, result, null)

            @JvmStatic
            fun error(error: String?): Response = Response(false, null, error)
        }
    }
}
