package com.github.catvod.utils

import android.util.Base64
import okhttp3.Request
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.regex.Pattern

object Auth {

    private val DIGEST = Pattern.compile("(\\w+)=(?:\"([^\"]*)\"|([^,\\s\"]+))")

    @JvmStatic
    fun basic(userInfo: String): String {
        val info = if (userInfo.contains(":")) userInfo else "$userInfo:"
        return "Basic " + Base64.encodeToString(info.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
    }

    @JvmStatic
    fun digest(userInfo: String, header: String, request: Request): String {
        val params = parseDigest(header.substring(7))
        val credentials = userInfo.split(":", limit = 2)
        val username = credentials[0]
        val password = if (credentials.size > 1) credentials[1] else ""
        val realm = params.getOrDefault("realm", "")
        val nonce = params.getOrDefault("nonce", "")
        val opaque = params["opaque"]
        val uri = digestUri(request)
        val qop = selectQop(params["qop"])
        val nc = "00000001"
        val cnonce = newCnonce()
        val ha1 = Util.md5("$username:$realm:$password")
        val ha2 = Util.md5("${request.method}:$uri")
        val response = digestResponse(ha1, ha2, nonce, nc, cnonce, qop)
        return buildHeader(username, realm, nonce, uri, nc, cnonce, qop, response, opaque)
    }

    private fun digestUri(request: Request): String {
        val query = request.url.encodedQuery
        val path = request.url.encodedPath
        return if (query != null) "$path?$query" else path
    }

    private fun digestResponse(ha1: String, ha2: String, nonce: String, nc: String, cnonce: String, qop: String): String {
        return if (qop.isEmpty()) Util.md5("$ha1:$nonce:$ha2")
        else Util.md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")
    }

    private fun buildHeader(username: String, realm: String, nonce: String, uri: String, nc: String, cnonce: String, qop: String, response: String, opaque: String?): String {
        val fields = mutableListOf<String>()
        fields.add("username=\"$username\"")
        fields.add("realm=\"$realm\"")
        fields.add("nonce=\"$nonce\"")
        fields.add("uri=\"$uri\"")
        val hasQop = qop.isNotEmpty()
        if (hasQop) fields.add("cnonce=\"$cnonce\"")
        if (hasQop) fields.add("nc=$nc")
        if (hasQop) fields.add("qop=$qop")
        fields.add("response=\"$response\"")
        if (opaque != null) fields.add("opaque=\"$opaque\"")
        return "Digest " + fields.joinToString(", ")
    }

    private fun newCnonce(): String {
        return UUID.randomUUID().toString().replace("-", "")
    }

    private fun selectQop(qop: String?): String {
        if (qop.isNullOrEmpty()) return ""
        for (option in qop.split(",")) if ("auth".equals(option.trim(), ignoreCase = true)) return "auth"
        return ""
    }

    private fun parseDigest(header: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        val matcher = DIGEST.matcher(header.trim())
        while (matcher.find()) {
            val key = matcher.group(1)
            val value: String? = matcher.group(2) ?: matcher.group(3)
            if (value != null) params[key!!] = value.trim()
        }
        return params
    }
}
