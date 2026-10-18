package com.wuming.tv.core.runtime.js

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

internal class JsHostBridge(
    private val gson: Gson = Gson(),
    private val environmentProxy: EnvironmentProxySelector = EnvironmentProxySelector(),
    private val storage: JsLocalStorage = InMemoryJsLocalStorage(),
) {

    fun request(location: String, optionsJson: String): String {
        val outer = URI(location)
        val embedded = outer.rawPath.removePrefix("/")
        val candidates = listOfNotNull(
            outer,
            embedded.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.let(::URI),
        ).distinct()
        val options = runCatching { gson.fromJson(optionsJson, JsonObject::class.java) }.getOrNull() ?: JsonObject()
        var failure: Throwable? = null
        candidates.forEachIndexed { index, uri ->
            try {
                val response = request(uri, options)
                if (response.first < 400 || index == candidates.lastIndex) return response.second
            } catch (error: Throwable) {
                failure = error
            }
        }
        throw failure ?: java.io.IOException("Provider request failed")
    }

    private fun request(uri: URI, options: JsonObject): Pair<Int, String> {
        require((uri.scheme == "http" || uri.scheme == "https") && uri.host != null && uri.userInfo == null) {
            "Provider requests must use HTTP(S)"
        }
        val asciiUri = URI(uri.toASCIIString())
        val connection = asciiUri.toURL().openConnection(environmentProxy.proxyFor(asciiUri)) as HttpURLConnection
        connection.connectTimeout = options.int("timeout", 10_000).coerceIn(1_000, 30_000)
        connection.readTimeout = connection.connectTimeout
        connection.instanceFollowRedirects = options.int("redirect", 1) != 0
        connection.requestMethod = options.string("method", "GET").uppercase().let { if (it == "HEADER") "HEAD" else it }
        options.getAsJsonObject("headers")?.entrySet()?.take(64)?.forEach { (name, value) ->
            if (name.length <= 128 && !FORBIDDEN_HEADERS.contains(name.lowercase())) {
                connection.setRequestProperty(name, value.asString.take(8_192))
            }
        }
        if (connection.requestMethod == "POST") {
            val body = options.string("body", "").ifBlank {
                options.get("data")?.let { data ->
                    if (options.string("postType", "json") == "form" && data.isJsonObject) {
                        data.asJsonObject.entrySet().joinToString("&") {
                            encode(it.key) + "=" + encode(it.value.asString)
                        }
                    } else gson.toJson(data)
                }.orEmpty()
            }.toByteArray(StandardCharsets.UTF_8)
            require(body.size <= MAX_BODY_BYTES) { "Provider request body exceeds size limit" }
            connection.doOutput = true
            connection.outputStream.use { it.write(body) }
        }
        val code = connection.responseCode
        val bytes = (if (code >= 400) connection.errorStream else connection.inputStream)
            ?.use { it.readNBytes(MAX_RESPONSE_BYTES + 1) } ?: byteArrayOf()
        require(bytes.size <= MAX_RESPONSE_BYTES) { "Provider response exceeds size limit" }
        val charset = connection.contentType?.substringAfter("charset=", "")?.substringBefore(';')
            ?.takeIf(String::isNotBlank)?.let { runCatching { Charset.forName(it.trim()) }.getOrNull() }
            ?: StandardCharsets.UTF_8
        val headers = connection.headerFields.filterKeys { it != null }.mapValues { it.value.joinToString(", ") }
        return code to gson.toJson(mapOf("code" to code, "content" to bytes.toString(charset), "headers" to headers))
    }

    fun get(namespace: String, key: String): String = storage.get(namespace, key)
    fun set(namespace: String, key: String, value: String) = storage.set(namespace, key, value)
    fun delete(namespace: String, key: String) = storage.delete(namespace, key)
    fun headers(json: String): Map<String, String> = runCatching {
        gson.fromJson(json, JsonObject::class.java).entrySet().associateTo(LinkedHashMap()) { (name, value) ->
            name.take(128) to value.asString.take(8_192)
        }
    }.getOrDefault(emptyMap())
    fun join(parent: String, child: String): String = URI(parent).resolve(child).toString()

    private fun JsonObject.string(name: String, fallback: String): String =
        runCatching { get(name)?.asString }.getOrNull() ?: fallback
    private fun JsonObject.int(name: String, fallback: Int): Int =
        runCatching { get(name)?.asInt }.getOrNull() ?: fallback

    private companion object {
        const val MAX_BODY_BYTES = 2 * 1024 * 1024
        const val MAX_RESPONSE_BYTES = 12 * 1024 * 1024
        val FORBIDDEN_HEADERS = setOf("host", "content-length", "connection", "upgrade")
        fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
    }
}
