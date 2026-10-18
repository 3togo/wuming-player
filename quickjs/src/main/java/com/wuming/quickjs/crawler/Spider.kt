package com.wuming.quickjs.crawler

import android.content.Context
import android.util.Log
import com.github.catvod.utils.Asset
import com.github.catvod.utils.Asset.read
import com.github.catvod.utils.Json
import com.github.catvod.utils.Json.isObj
import com.github.catvod.utils.Json.toMap
import com.github.catvod.utils.UriUtil
import com.github.catvod.utils.Util
import com.whl.quickjs.wrapper.JSArray
import com.whl.quickjs.wrapper.JSObject
import com.whl.quickjs.wrapper.QuickJSContext
import com.whl.quickjs.wrapper.QuickJSContext.BytecodeModuleLoader
import com.wuming.quickjs.bean.Res
import com.wuming.quickjs.bean.Res.Companion.objectFrom
import com.wuming.quickjs.method.Console
import com.wuming.quickjs.method.Global
import com.wuming.quickjs.method.Local
import com.wuming.quickjs.utils.Async
import com.wuming.quickjs.utils.JSUtil
import com.wuming.quickjs.utils.JSUtil.toArray
import com.wuming.quickjs.utils.JSUtil.toObject
import com.wuming.quickjs.utils.Module
import com.wuming.quickjs.utils.Module.Companion.get
import dalvik.system.DexClassLoader
import java.io.ByteArrayInputStream
import java.util.Arrays
import java.util.HashMap
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import kotlin.concurrent.Volatile
import org.json.JSONArray

open class Spider constructor(private val api: String, private val dex: DexClassLoader) :
    com.github.catvod.crawler.Spider() {
    private val executor: ExecutorService

    private var ctx: QuickJSContext? = null
    private var jsObject: JSObject? = null

    @Volatile
    private var global: Global? = null
    private val destroyed = AtomicBoolean()
    private val pending: MutableSet<CompletableFuture<*>?> =
        ConcurrentHashMap.newKeySet<CompletableFuture<*>?>()
    private var cat = false

    init {
        this.executor = Executors.newSingleThreadExecutor()
    }

    @Synchronized
    private fun <T> submit(callable: Callable<T?>): Future<T?> {
        if (destroyed.get()) throw RejectedExecutionException("Spider is destroyed")
        return executor.submit<T?>(callable)
    }

    @Throws(Exception::class)
    private fun call(func: String, vararg args: Any?): Any? {
        return submit<CompletableFuture<Any?>?>(
            java.util.concurrent.Callable {
                try {
                    val result: java.util.concurrent.CompletableFuture<kotlin.Any?> =
                        Async.Companion.run(jsObject!!, func, *args)
                    pending.add(result)
                    result.whenComplete(
                        java.util.function.BiConsumer {
                                value: kotlin.Any?,
                                error: kotlin.Throwable?
                            ->
                            pending.remove(
                                result
                            )
                        }
                    )
                    if (destroyed.get()) result.cancel(false)
                    return@Callable result
                } finally {
                    // Call arguments were created for this invocation. JavaScript
                    // retains its own references if an async function needs them.
                    for (arg: kotlin.Any? in args) if (arg is com.whl.quickjs.wrapper.JSObject) arg.release()
                }
            }
        ).get()!!.get()
    }

    @Throws(Exception::class)
    override fun init(context: Context?, extend: String?) {
        initializeJS()
        call("init", submit<Any?>(Callable { getExt(extend) }).get())
    }

    @Throws(Exception::class)
    override fun homeContent(filter: Boolean): String? = callString("home", filter)

    @Throws(Exception::class)
    override fun homeVideoContent(): String? = callString("homeVod")

    @Throws(Exception::class)
    override fun categoryContent(
        tid: String?,
        pg: String?,
        filter: Boolean,
        extend: HashMap<String, String>?
    ): String? {
        val obj: JSObject? = submit<JSObject?>(Callable { toObject(ctx!!, extend) }).get()
        return callString("category", tid, pg, filter, obj)
    }

    @Throws(Exception::class)
    override fun detailContent(ids: MutableList<String>): String? = callString("detail", ids.get(0))

    @Throws(Exception::class)
    override fun searchContent(key: String?, quick: Boolean): String? =
        callString("search", key, quick)

    @Throws(Exception::class)
    override fun searchContent(key: String?, quick: Boolean, pg: String?): String? =
        callString("search", key, quick, pg)

    @Throws(Exception::class)
    override fun playerContent(
        flag: String?,
        id: String?,
        vipFlags: MutableList<String>?
    ): String? {
        val array: JSArray? = submit<JSArray?>(Callable { toArray(ctx!!, vipFlags) }).get()
        return callString("play", flag, id, array)
    }

    @Throws(Exception::class)
    override fun liveContent(url: String?): String? = callString("live", url)

    @Throws(Exception::class)
    override fun manualVideoCheck(): Boolean = (call("sniffer") as kotlin.Boolean?)!!

    @Throws(Exception::class)
    override fun isVideoFormat(url: String): Boolean = (call("isVideo", url) as kotlin.Boolean?)!!

    @Throws(Exception::class)
    override fun proxy(params: MutableMap<String, String>): Array<Any?> =
        if ("catvod" == params.get("from")) proxy2(params) else proxy1(params)

    @Throws(Exception::class)
    override fun action(action: String?): String? = callString("action", action)

    @Synchronized
    override fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        pending.forEach(Consumer { future: CompletableFuture<*>? -> future!!.cancel(false) })
        pending.clear()
        val current: Global? = global
        if (current != null) current.cancelPending()
        // Never wait for a provider's destroy promise on a UI/configuration thread.
        // Native objects must still be released on the thread that created them.
        executor.execute(
            Runnable {
                try {
                    if (jsObject != null) Async.Companion.run(jsObject!!, "destroy")
                } catch (e: Throwable) {
                    Log.e("Spider", "JS destroy failed", e)
                } finally {
                    try {
                        if (global != null) global!!.destroy()
                    } finally {
                        global = null
                        try {
                            if (jsObject != null) jsObject!!.release()
                        } finally {
                            jsObject = null
                            if (ctx != null) ctx!!.destroy()
                            ctx = null
                        }
                    }
                }
            }
        )
        executor.shutdown()
    }

    @Throws(Exception::class)
    private fun initializeJS() {
        submit<Any?>(
            Callable {
                createCtx()
                createFun()
                createObj()
                null
            }
        ).get()
    }

    private fun createCtx() {
        ctx = QuickJSContext.create()
        ctx!!.setConsole(Console())
        ctx!!.evaluate(read("js/lib/http.js"))
        ctx!!.getGlobalObject().setProperty("local", Local::class.java)
        ctx!!.setModuleLoader(object : BytecodeModuleLoader() {
            override fun moduleNormalizeName(baseModuleName: String?, moduleName: String?): String =
                UriUtil.resolve(baseModuleName, moduleName)

            override fun getModuleBytecode(moduleName: String): ByteArray? =
                ctx!!.compileModule(get().fetch(moduleName), moduleName)
        })
    }

    private fun createFun() {
        try {
            global = Global.create(ctx!!, executor)
            val clz: Class<*> = dex.loadClass("com.github.catvod.js.Function")
            clz.getDeclaredConstructor(QuickJSContext::class.java).newInstance(ctx)
        } catch (e: Throwable) {
            Log.e("Spider", "JS Function class load failed", e)
        }
    }

    private fun createObj() {
        val spider: String = "__JS_SPIDER__"
        val global: String = "globalThis." + spider
        val content: String? = get().fetch(api)
        cat = content!!.contains("__jsEvalReturn")
        ctx!!.evaluateModule(content.replace(spider, global), api)
        ctx!!.evaluateModule(String.format(read("js/lib/spider.js"), api))
        jsObject = ctx!!.getProperty(ctx!!.getGlobalObject(), spider) as JSObject?
    }

    private fun getExt(ext: String?): Any? {
        if (!cat) return if (isObj(ext)) ctx!!.parse(ext) else ext
        val obj: JSObject = ctx!!.createNewJSObject()
        obj.setProperty("stype", 3)
        obj.setProperty("skey", siteKey)
        if (!isObj(ext)) {
            obj.setProperty("ext", ext)
        } else {
            val parsed: JSObject = ctx!!.parse(ext) as JSObject
            try {
                obj.setProperty("ext", parsed)
            } finally {
                parsed.release()
            }
        }
        return obj
    }

    /** CatVod JavaScript providers legitimately return either JSON text or a JavaScript value.  */
    @Throws(Exception::class)
    private fun callString(func: String, vararg args: Any?): String? {
        val value: Any? = call(func, *args)
        if (value == null) return ""
        if (value !is JSObject) return value.toString()
        return submit<String?>(
            Callable {
                try {
                    return@Callable value.stringify()
                } finally {
                    value.release()
                }
            }
        ).get()
    }

    @Throws(Exception::class)
    private fun proxy1(params: MutableMap<String, String>?): Array<Any?> {
        val obj: JSObject = submit<JSObject?>(
            java.util.concurrent.Callable {
                toObject(
                    ctx!!,
                    params
                )
            }
        ).get()!!
        val proxy: JSArray = call("proxy", obj) as JSArray
        val json: String? = submit<String?>(
            Callable {
                try {
                    return@Callable proxy.stringify()
                } finally {
                    proxy.release()
                }
            }
        ).get()
        val array: JSONArray = JSONArray(json)
        val headers: Map<String, String>? =
            if (array.length() > 3) toMap(array.optString(3)) else null
        val base64: Boolean = array.length() > 4 && array.optInt(4) == 1
        val result: Array<Any?> = arrayOfNulls<Any>(4)
        result[0] = array.optInt(0)
        result[1] = array.optString(1)
        result[2] = getStream(array.opt(2), base64)
        result[3] = headers
        return result
    }

    @Throws(Exception::class)
    private fun proxy2(params: MutableMap<String, String>): Array<Any?> {
        val url: String = params.get("url")!!
        val header: String? = params.get("header")
        val array: JSArray = submit<JSArray?>(
            java.util.concurrent.Callable {
                toArray(
                    ctx!!,
                    java.util.Arrays.asList<kotlin.String>(
                        *url.split("/".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                    )
                )
            }
        ).get()!!
        val `object`: Any? = submit<Any?>(Callable { ctx!!.parse(header) }).get()
        val proxy: String? = callString("proxy", array, `object`)
        val res: Res = objectFrom(proxy)
        val result: Array<Any?> = arrayOfNulls<Any>(3)
        result[0] = res.getCode()
        result[1] = res.getContentType()
        result[2] = res.getStream()
        return result
    }

    private fun getStream(o: Any, base64: Boolean): ByteArrayInputStream {
        if (o is ByteArray) {
            return ByteArrayInputStream(o)
        } else {
            var content: String = o.toString()
            if (base64 && content.contains("base64,")) {
                content =
                    content.split("base64,".toRegex()).dropLastWhile {
                        it.isEmpty()
                    }.toTypedArray()[1]
            }
            return ByteArrayInputStream(if (base64) Util.decode(content) else content.toByteArray())
        }
    }
}
