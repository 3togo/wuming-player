package com.wuming.android.tv.api

import com.wuming.android.tv.setting.AppSettings
import com.wuming.android.tv.utils.AppLog
import com.github.catvod.net.OkHttp
import com.github.catvod.utils.Json
import com.wuming.tv.core.source.TvBoxConfigPayloadDecoder

import java.net.URLEncoder
import java.io.IOException
import java.nio.charset.StandardCharsets


object Decoder {

    private const val TAG = "Decoder"

    @JvmStatic
    @Throws(Exception::class)
    fun getJson(url: String, tag: String): String = fetch(url, tag, false)

    @JvmStatic
    @Throws(Exception::class)
    fun getJsonObject(url: String, tag: String): String = fetch(url, tag, true)

    @Throws(Exception::class)
    private fun fetch(url: String, tag: String, requireObject: Boolean): String {
        try {
            val result = trim(getJsonDirect(url, tag))
            if (requireObject && !Json.isObj(result)) throw IOException("Direct response is not JSON")
            AppLog.i(TAG, "Direct fetch succeeded")
            return result
        } catch (e: Exception) {
            if (Thread.currentThread().isInterrupted || "Canceled" == e.message) throw e
            if (runCatching { java.net.URI(url).host in setOf("127.0.0.1", "localhost", "::1", "[::1]") }.getOrDefault(false)) throw e
            AppLog.d(TAG, "Direct fetch failed; using relay", e)
            try {
                val result = trim(getJsonRelay(url, tag))
                if (requireObject && !Json.isObj(result)) throw IOException("Relay response is not JSON")
                return result
            } catch (relayError: Exception) {
                relayError.addSuppressed(e)
                throw relayError
            }
        }
    }

    /** Without this a host that appends anything after the object parses as an empty config. */
    private fun trim(text: String): String = TvBoxConfigPayloadDecoder.trimToJsonDocument(text)

    @Throws(Exception::class)
    private fun getJsonDirect(url: String, tag: String): String {
        OkHttp.newCall(url, tag).execute().use { res ->
            if (!res.isSuccessful) throw IOException("Configuration HTTP " + res.code)
            val httpUrl = res.request.url
            var u = url
            if (httpUrl.querySize == 0) u = httpUrl.toString()
            return verify(u, res.body.string())
        }
    }

    @Throws(Exception::class)
    private fun getJsonRelay(url: String, tag: String): String {
        val relayUrl = AppSettings.getRelayUrl() + URLEncoder.encode(url, StandardCharsets.UTF_8.name())
        OkHttp.newCall(relayUrl, tag).execute().use { res ->
            if (!res.isSuccessful) throw IOException("Configuration relay HTTP " + res.code)
            val data = trim(res.body.string())
            if (data.isEmpty()) throw IOException("Configuration relay response is empty")
            val decoded = verify(url, data)
            if (!Json.isObj(decoded)) throw IOException("Configuration relay response is not a JSON object")
            return decoded
        }
    }

    @Throws(Exception::class)
    private fun verify(url: String, data: String): String {
        val normalized = trim(data)
        require(normalized.isNotEmpty()) { "Configuration response is empty" }
        if (!Json.isObj(normalized) && !normalized.startsWith("2423") && !Regex("[A-Za-z0-9]{8}\\*\\*").containsMatchIn(normalized)) return fix(url, normalized)
        return fix(url, TvBoxConfigPayloadDecoder.decode(normalized.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun fix(url: String, data: String): String {
        // LiveConfig also accepts M3U/TVBox TXT. Preserve its existing relative-text behavior.
        if (!Json.isObj(data)) return data.replace("../", com.wuming.tv.core.source.config.ConfigResourceResolver.resolve(url, "../")).replace("./", com.wuming.tv.core.source.config.ConfigResourceResolver.resolve(url, "./"))
        val root = com.google.gson.JsonParser.parseString(data).asJsonObject
        return com.wuming.tv.core.source.config.ConfigResourceResolver.rewrite(root) {
            com.wuming.tv.core.source.config.ConfigResourceResolver.resolve(url, it)
        }.toString()
    }

}
