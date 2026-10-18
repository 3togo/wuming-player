package com.wuming.tv.core.runtime.jvmspider

import com.google.gson.Gson
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.nio.charset.StandardCharsets

/** Child-process entry point. Provider stdout is redirected to stderr so JSON RPC stays framed. */
object JvmSpiderHostMain {

    private val gson = Gson()

    @JvmStatic
    fun main(args: Array<String>) {
        val rpcOut = System.out
        System.setOut(PrintStream(System.err, true, StandardCharsets.UTF_8))
        val className = args.firstOrNull()
        if (className.isNullOrBlank()) {
            rpcOut.println(gson.toJson(JvmSpiderHostResponse(error = "JVM spider class name is missing")))
            return
        }
        val spider = runCatching { Class.forName(className).getDeclaredConstructor().newInstance() }
            .getOrElse {
                rpcOut.println(gson.toJson(JvmSpiderHostResponse(error = "Unable to load JVM spider: ${rootCause(it).message}")))
                return
            }
        args.getOrNull(1)?.let { key ->
            runCatching { spider.javaClass.getField("siteKey").set(spider, key) }
        }
        BufferedReader(InputStreamReader(System.`in`, StandardCharsets.UTF_8)).useLines { lines ->
            lines.forEach { line ->
                val response = runCatching {
                    val request = gson.fromJson(line, JvmSpiderHostRequest::class.java)
                    JvmSpiderHostResponse(true, invoke(spider, request))
                }.getOrElse { JvmSpiderHostResponse(error = rootCause(it).message ?: it.javaClass.simpleName) }
                rpcOut.println(gson.toJson(response))
                rpcOut.flush()
                if (runCatching { gson.fromJson(line, JvmSpiderHostRequest::class.java).operation }.getOrNull() == "close") {
                    return
                }
            }
        }
    }

    private fun invoke(spider: Any, request: JvmSpiderHostRequest): String? = when (request.operation) {
        "init" -> {
            val withExtension = method(spider, "init", String::class.java)
            if (withExtension != null) withExtension.invoke(spider, request.extension.orEmpty())
            else method(spider, "init")?.invoke(spider)
            "{}"
        }
        "home" -> required(spider, "homeContent", Boolean::class.javaPrimitiveType!!)
            .invoke(spider, true) as? String
        "category" -> required(
            spider,
            "categoryContent",
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType!!,
            HashMap::class.java,
        ).invoke(
            spider,
            request.id.orEmpty(),
            request.page.orEmpty().ifBlank { "1" },
            true,
            HashMap(request.filters),
        ) as? String
        "search" -> required(spider, "searchContent", String::class.java, Boolean::class.javaPrimitiveType!!)
            .invoke(spider, request.query.orEmpty(), false) as? String
        "detail" -> required(spider, "detailContent", List::class.java)
            .invoke(spider, listOf(request.id.orEmpty())) as? String
        "play" -> required(spider, "playerContent", String::class.java, String::class.java, List::class.java)
            .invoke(spider, request.flag.orEmpty(), request.id.orEmpty(), emptyList<String>()) as? String
        "close" -> {
            method(spider, "destroy")?.invoke(spider)
            "{}"
        }
        else -> throw IllegalArgumentException("Unsupported JVM spider operation")
    }

    private fun required(spider: Any, name: String, vararg parameters: Class<*>): java.lang.reflect.Method =
        method(spider, name, *parameters) ?: throw IllegalArgumentException("JVM spider does not implement $name")

    private fun method(spider: Any, name: String, vararg parameters: Class<*>): java.lang.reflect.Method? =
        runCatching { spider.javaClass.getMethod(name, *parameters) }.getOrNull()

    private fun rootCause(failure: Throwable): Throwable {
        var current = if (failure is InvocationTargetException && failure.targetException != null) {
            failure.targetException
        } else failure
        while (current.cause != null && current.cause !== current) current = current.cause!!
        return current
    }
}
