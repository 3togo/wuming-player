package com.wuming.tv.core.runtime.python

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.File

/** A process-owned CPython runtime. Providers are trusted code, not sandboxed. */
object NativePythonBridge {
    @Volatile private var startedAt: String? = null
    private val gson = Gson()

    @Synchronized fun start(home: File, modulePaths: List<File>, library: File? = null) {
        val identity = home.canonicalPath
        startedAt?.let { require(it == identity) { "CPython already uses another runtime home" }; return }
        if (library == null) System.loadLibrary("wuming_python")
        else {
            require(library.isFile) { "Native Python bridge is not packaged: $library" }
            System.load(library.absolutePath)
        }
        startNative(identity, modulePaths.map { it.canonicalPath }.toTypedArray())
        startedAt = identity
    }

    fun request(value: Map<String, Any?>): JsonElement {
        check(startedAt != null) { "Native Python runtime is unavailable" }
        val bytes = gson.toJson(value).toByteArray(Charsets.UTF_8)
        require(bytes.size <= 8 * 1024 * 1024) { "Python request is too large" }
        val result = JsonParser.parseString(dispatchNative(bytes).toString(Charsets.UTF_8)).asJsonObject
        if (!result.get("ok").asBoolean) throw IllegalStateException(result.get("error").asString)
        return result.get("value")
    }

    private external fun startNative(home: String, modulePaths: Array<String>)
    private external fun dispatchNative(request: ByteArray): ByteArray
}
