package com.wuming.tv.core.source.diagnostic

import java.net.URI
import java.util.LinkedHashMap
import java.util.Locale
import java.util.regex.Pattern

object DiagnosticSanitizer {
    private const val MAX_VALUE_LENGTH = 2048
    private val SECRET_KEYS =
        setOf(
            "authorization",
            "cookie",
            "set-cookie",
            "token",
            "access_token",
            "refresh_token",
            "password",
            "passwd",
            "secret",
            "key",
            "sign",
            "signature",
        )
    private val JSON_SECRET =
        Pattern.compile(
            "(?i)(\\\"?(?:authorization|cookie|set-cookie|access_token|refresh_token|token|password|passwd|secret|sign|signature)\\\"?\\s*[:=]\\s*\\\"?)([^\\\",&}\\s]+)",
        )
    private val BEARER = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*")
    private val URL = Pattern.compile("https?://[^\\s\\\"'<>]+", Pattern.CASE_INSENSITIVE)

    @JvmStatic fun sanitize(evidence: Map<out String?, String?>?): Map<String, String> {
        if (evidence.isNullOrEmpty()) return emptyMap()
        val output = LinkedHashMap<String, String>()
        evidence.forEach { (key, value) ->
            val safeKey = key?.trim { it <= ' ' } ?: ""
            if (safeKey.isNotEmpty()) output[safeKey] = if (isSecretKey(safeKey)) "<redacted>" else sanitizeText(value)
        }
        return java.util.Map.copyOf(output)
    }

    @JvmStatic fun sanitizeText(value: String?): String {
        if (value == null) return ""
        var redacted = BEARER.matcher(value).replaceAll("Bearer <redacted>")
        redacted = JSON_SECRET.matcher(redacted).replaceAll("$1<redacted>")
        redacted = redactUrls(redacted)
        return if (redacted.length <= MAX_VALUE_LENGTH) redacted else redacted.substring(0, MAX_VALUE_LENGTH) + "…"
    }

    private fun redactQuery(value: String): String {
        if (!(value.startsWith("http://") || value.startsWith("https://"))) return value
        try {
            val uri = URI.create(value)
            if (uri.rawQuery == null) return value
            return URI(uri.scheme, uri.rawAuthority, uri.rawPath, "<redacted>", null).toString()
        } catch (_: Exception) {
            val query = value.indexOf('?')
            return if (query < 0) value else value.substring(0, query) + "?<redacted>"
        }
    }

    private fun isSecretKey(value: String): Boolean {
        val normalized = value.lowercase(Locale.ROOT)
        if (SECRET_KEYS.contains(normalized)) return true
        val separator = maxOf(normalized.lastIndexOf('.'), normalized.lastIndexOf('-'))
        return separator >= 0 && SECRET_KEYS.contains(normalized.substring(separator + 1))
    }

    private fun redactUrls(value: String): String {
        val matcher = URL.matcher(value)
        val output = StringBuffer()
        while (matcher.find()) {
            matcher.appendReplacement(
                output,
                java.util.regex.Matcher
                    .quoteReplacement(redactQuery(matcher.group())),
            )
        }
        matcher.appendTail(output)
        return output.toString()
    }
}
