package com.wuming.tv.core.source

import java.nio.charset.StandardCharsets
import java.util.Base64

/** Decodes plain JSON and the TVBox image-plus-Base64 configuration envelope. */
object TvBoxConfigPayloadDecoder {

    private val marker = Regex("[A-Za-z0-9]{8}\\*\\*")

    fun decode(payload: ByteArray): String {
        require(payload.isNotEmpty()) { "Configuration response is empty." }
        val direct = trimToJsonDocument(withoutByteOrderMark(payload.toString(StandardCharsets.UTF_8)))
        if (direct.startsWith("{")) return direct
        if (direct.startsWith("2423")) return decodeCbc(direct)

        val envelope = payload.toString(StandardCharsets.ISO_8859_1)
        val match = marker.find(envelope)
            ?: throw IllegalArgumentException("Configuration is neither JSON nor a supported encoded envelope.")
        val encoded = envelope.substring(match.range.last + 1).trim()
        require(encoded.isNotEmpty()) { "Encoded configuration payload is empty." }
        val decoded = trimToJsonDocument(runCatching { Base64.getMimeDecoder().decode(encoded) }
            .getOrElse { throw IllegalArgumentException("Encoded configuration payload is invalid.") }
            .toString(StandardCharsets.UTF_8)
            .trim())
        if (decoded.startsWith("2423")) return decodeCbc(decoded)
        require(decoded.startsWith("{")) { "Decoded configuration is not a JSON object." }
        return decoded
    }

    /**
     * Drops content a host appended after the configuration object, such as an analytics
     * snippet or a closing HTML tag. Gson's lenient parser rejects anything after the
     * document, and `catvod`'s `Json.parse` turns that into a silent empty config.
     *
     * Accepts leading line/block comments used by hosted TVBox configurations and ignores
     * comment braces while locating the end. Comments inside strings remain literal text.
     * Only an object-shaped document is trimmed: an array or a page that merely contains
     * braces is handed through untouched so the normal error path still reports it.
     */
    @JvmStatic
    fun trimToJsonDocument(text: String): String {
        val original = withoutByteOrderMark(text.trim()).trimStart()
        var start = 0
        while (start < original.length) {
            when {
                original[start].isWhitespace() -> start++
                original.startsWith("//", start) -> {
                    while (start < original.length && original[start] != '\n' && original[start] != '\r') start++
                }
                original.startsWith("/*", start) -> {
                    val end = original.indexOf("*/", start + 2)
                    if (end < 0) return original
                    start = end + 2
                }
                else -> break
            }
        }
        val trimmed = original.substring(start)
        if (!trimmed.startsWith("{")) return original
        var depth = 0
        var inString = false
        var escaped = false
        var lineComment = false
        var blockComment = false
        for (index in trimmed.indices) {
            val c = trimmed[index]
            if (lineComment) {
                if (c == '\n' || c == '\r') lineComment = false
            } else if (blockComment) {
                if (c == '/' && index > 0 && trimmed[index - 1] == '*') blockComment = false
            } else if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                if (c == '/' && trimmed.getOrNull(index + 1) == '/') {
                    lineComment = true
                    continue
                }
                if (c == '/' && trimmed.getOrNull(index + 1) == '*') {
                    blockComment = true
                    continue
                }
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return trimmed.substring(0, index + 1)
                    }
                }
            }
        }
        return trimmed
    }

    /** Compatibility with Android's self-contained TVBox 2423 envelope, not secret storage. */
    private fun decodeCbc(value: String): String {
        val hex = value.replace(Regex("\\s+"), "")
        require(hex.length in 32..(16 * 1024 * 1024) && hex.length % 2 == 0 && hex.all { it in "0123456789abcdefABCDEF" }) { "Invalid CBC envelope" }
        fun bytes(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val marker = hex.indexOf("2324", startIndex = 4)
        require(marker >= 4 && marker % 2 == 0 && marker + 4 < hex.length - 26) { "Invalid CBC framing" }
        val keyText = String(bytes(hex.substring(4, marker)), Charsets.UTF_8).lowercase(java.util.Locale.ROOT)
        val ivText = String(bytes(hex.takeLast(26)), Charsets.UTF_8).lowercase(java.util.Locale.ROOT)
        fun padded(text: String) = text.take(16).padEnd(16, '0').toByteArray(Charsets.UTF_8)
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(padded(keyText), "AES"), javax.crypto.spec.IvParameterSpec(padded(ivText)))
        val json = trimToJsonDocument(String(cipher.doFinal(bytes(hex.substring(marker + 4, hex.length - 26))), Charsets.UTF_8))
        require(json.startsWith("{")) { "Decoded configuration is not a JSON object" }
        return json
    }

    /** Hosted configurations are frequently saved with a UTF-8 BOM, which [String.trim] keeps. */
    private fun withoutByteOrderMark(value: String): String {
        var start = 0
        while (start < value.length && value[start] == BYTE_ORDER_MARK) start++
        return if (start == 0) value else value.substring(start)
    }

    private const val BYTE_ORDER_MARK = '\uFEFF'
}

enum class TvBoxSourceRuntime {
    DIRECT_JAVASCRIPT,
    SPIDER_RUNTIME_REQUIRED,
    /** Android `type` 0/1: a MacCMS collection API served over plain HTTP GET. */
    MAC_CMS_API,
    /** TVBox `type` 4: a remote CatVod-compatible HTTP endpoint. */
    REMOTE_SPIDER_HTTP,
    UNSUPPORTED_TYPE,
}

object TvBoxSourceRuntimePolicy {

    fun classify(type: Int, api: String): TvBoxSourceRuntime = when {
        type != 3 -> {
            when {
                type in setOf(0, 1) && isHttpApi(api) -> TvBoxSourceRuntime.MAC_CMS_API
                type == 4 && api.isNotBlank() -> TvBoxSourceRuntime.REMOTE_SPIDER_HTTP
                else -> TvBoxSourceRuntime.UNSUPPORTED_TYPE
            }
        }
        api.substringBefore('?').lowercase().endsWith(".js") -> TvBoxSourceRuntime.DIRECT_JAVASCRIPT
        else -> TvBoxSourceRuntime.SPIDER_RUNTIME_REQUIRED
    }

    private fun isHttpApi(api: String): Boolean =
        api.startsWith("http://", ignoreCase = true) || api.startsWith("https://", ignoreCase = true)
}
