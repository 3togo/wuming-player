package com.wuming.tv.core.runtime.cloudlogin

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

data class CloudLoginResponse(
    val body: JsonObject,
    val setCookies: List<String> = emptyList(),
)

data class CloudLoginTextResponse(
    val body: String,
    val setCookies: List<String> = emptyList(),
)

interface CloudLoginTransport {
    fun get(url: String, query: Map<String, String> = emptyMap(), headers: Map<String, String> = emptyMap()): CloudLoginResponse
    fun getText(
        url: String,
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): CloudLoginTextResponse = get(url, query, headers).let { CloudLoginTextResponse(it.body.toString(), it.setCookies) }
    fun postForm(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap()): CloudLoginResponse
    fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): CloudLoginResponse
}

class JdkCloudLoginTransport(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
) : CloudLoginTransport {

    override fun get(url: String, query: Map<String, String>, headers: Map<String, String>): CloudLoginResponse =
        parse(send(HttpRequest.newBuilder(target(url, query)).GET(), headers))

    override fun getText(
        url: String,
        query: Map<String, String>,
        headers: Map<String, String>,
    ): CloudLoginTextResponse = send(HttpRequest.newBuilder(target(url, query)).GET(), headers)

    override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): CloudLoginResponse =
        parse(send(
            HttpRequest.newBuilder(target(url)).POST(HttpRequest.BodyPublishers.ofString(encoded(form))),
            headers + ("Content-Type" to "application/x-www-form-urlencoded"),
        ))

    override fun postJson(url: String, json: String, headers: Map<String, String>): CloudLoginResponse =
        parse(send(
            HttpRequest.newBuilder(target(url)).POST(HttpRequest.BodyPublishers.ofString(json)),
            headers + ("Content-Type" to "application/json"),
        ))

    private fun send(builder: HttpRequest.Builder, headers: Map<String, String>): CloudLoginTextResponse {
        headers.forEach(builder::header)
        val response = client.send(
            builder.timeout(Duration.ofSeconds(30)).header("Accept", "application/json").build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )
        response.body().use { stream ->
            val bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1)
            if (bytes.size > MAX_RESPONSE_BYTES) throw IOException("Cloud login response is too large")
            if (response.statusCode() !in 200..299) {
                throw IOException("Cloud login request failed (HTTP ${response.statusCode()})")
            }
            return CloudLoginTextResponse(
                String(bytes, StandardCharsets.UTF_8).trim(),
                response.headers().allValues("Set-Cookie"),
            )
        }
    }

    private fun parse(response: CloudLoginTextResponse): CloudLoginResponse {
        val parsed = runCatching { JsonParser.parseString(stripJsonp(response.body)) }.getOrElse {
            throw IOException("Cloud login provider returned invalid JSON", it)
        }
        if (!parsed.isJsonObject) throw IOException("Cloud login provider returned invalid JSON")
        return CloudLoginResponse(parsed.asJsonObject, response.setCookies)
    }

    private fun target(url: String, query: Map<String, String> = emptyMap()): URI {
        val base = URI(url)
        require(base.scheme.equals("https", true) && !base.host.isNullOrBlank() && base.userInfo == null) {
            "Cloud login endpoint must be HTTPS"
        }
        if (query.isEmpty()) return base
        val separator = if (base.rawQuery.isNullOrEmpty()) "?" else "&"
        return URI.create(url + separator + encoded(query))
    }

    private fun encoded(values: Map<String, String>): String = values.entries.joinToString("&") {
        encode(it.key) + "=" + encode(it.value)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun stripJsonp(value: String): String {
        val open = value.indexOf('(')
        return if (open > 0 && value.endsWith(')')) value.substring(open + 1, value.length - 1) else value
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 5 * 1024 * 1024
    }
}
