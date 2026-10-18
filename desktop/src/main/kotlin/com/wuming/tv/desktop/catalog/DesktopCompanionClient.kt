package com.wuming.tv.desktop.catalog

import com.wuming.tv.core.catalog.CatalogDetail
import com.wuming.tv.core.catalog.CatalogError
import com.wuming.tv.core.catalog.CatalogGateway
import com.wuming.tv.core.catalog.CatalogItem
import com.wuming.tv.core.catalog.CatalogPlayback
import com.wuming.tv.core.catalog.CatalogSource
import com.wuming.tv.core.runtime.js.EnvironmentProxySelector
import com.wuming.tv.core.source.CompanionProtocol
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

class DesktopCompanionClient(
    address: String,
    token: String = "",
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .proxy(EnvironmentProxySelector())
        .build(),
    private val gson: Gson = Gson(),
) : CatalogGateway {

    val baseUri: URI = normalizeAddress(address)
    var token: String = token
        private set

    fun pair(): String {
        val response = try {
            send(CompanionProtocol.PAIR, emptyMap(), authenticated = false)
        } catch (error: CompanionException) {
            if (error.statusCode == 403) {
                throw CompanionException("Open the pairing screen on the Android TV, then try again.", 403)
            }
            throw error
        }
        val value = response.body().trim()
        require(value.matches(Regex(CompanionProtocol.TOKEN_PATTERN))) {
            "The companion returned an invalid pairing token."
        }
        token = value
        return value
    }

    override fun sources(): List<CatalogSource> = decode(
        send(CompanionProtocol.SOURCES, emptyMap()).body(),
        object : TypeToken<List<CatalogSource>>() {}.type,
    )

    override fun search(source: String, query: String): List<CatalogItem> {
        val validSource = CompanionProtocol.requireParameter(source, "source")
        val validQuery = CompanionProtocol.requireParameter(query, "query")
        return decode(
            send(CompanionProtocol.SEARCH, mapOf("source" to validSource, "query" to validQuery)).body(),
            object : TypeToken<List<CatalogItem>>() {}.type,
        )
    }

    override fun detail(source: String, id: String): CatalogDetail {
        val validSource = CompanionProtocol.requireParameter(source, "source")
        val validId = CompanionProtocol.requireParameter(id, "id")
        return decode(
            send(CompanionProtocol.DETAIL, mapOf("source" to validSource, "id" to validId)).body(),
            CatalogDetail::class.java,
        )
    }

    override fun play(source: String, flag: String, id: String): CatalogPlayback {
        val validSource = CompanionProtocol.requireParameter(source, "source")
        val validFlag = CompanionProtocol.requireParameter(flag, "flag")
        val validId = CompanionProtocol.requireParameter(id, "id")
        return decode(
            send(CompanionProtocol.PLAY, mapOf("source" to validSource, "flag" to validFlag, "id" to validId)).body(),
            CatalogPlayback::class.java,
        )
    }

    private fun send(
        path: String,
        parameters: Map<String, String>,
        authenticated: Boolean = true,
    ): HttpResponse<String> {
        if (authenticated) require(token.matches(Regex(CompanionProtocol.TOKEN_PATTERN))) {
            "Pair with the Android TV companion first."
        }
        val query = parameters.entries.joinToString("&") {
            encode(it.key) + "=" + encode(it.value)
        }
        val target = baseUri.resolve(path + if (query.isEmpty()) "" else "?$query")
        val builder = HttpRequest.newBuilder(target)
            .timeout(Duration.ofSeconds(60))
            .header("Accept", "application/json")
            .GET()
        if (authenticated) builder.header("Authorization", "Bearer $token")
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() !in 200..299) {
            val message = runCatching { gson.fromJson(response.body(), CatalogError::class.java).error }
                .getOrNull().orEmpty().ifBlank {
                    if (response.statusCode() == 401) "Pairing required or expired." else "HTTP ${response.statusCode()}"
                }
            throw CompanionException(message, response.statusCode())
        }
        return response
    }

    private fun <T> decode(json: String, type: java.lang.reflect.Type): T =
        gson.fromJson<T>(json, type) ?: throw CompanionException("Companion returned an empty response.")

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun normalizeAddress(value: String): URI {
        val input = value.trim().trimEnd('/')
        val uri = runCatching { URI(input) }.getOrElse { throw IllegalArgumentException("Invalid companion address.") }
        require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
            "Companion address must use HTTP or HTTPS."
        }
        require(!uri.host.isNullOrBlank()) { "Companion address has no host." }
        require(uri.rawQuery == null && uri.rawFragment == null) { "Companion address must not contain a query or fragment." }
        return URI(uri.scheme.lowercase(), null, uri.host, uri.port, "/", null, null)
    }
}

class CompanionException(message: String, val statusCode: Int = 0) : Exception(message)
