package com.wuming.tv.core.libvio

import java.security.MessageDigest

/** Implements only the site's published automatic PoW page; interactive challenges remain explicit failures. */
internal class LibvioBrowserSession(private val transport: LibvioTransport) {
    fun get(url: String): String {
        var body = transport.get(url, HEADERS)
        if ("var POW =" in body) {
            val ts = variable(body, "TS", "[0-9]{1,16}")
            val sig = variable(body, "SIG", "[a-f0-9]{64}")
            val diff = variable(body, "DIFF", "0{1,4}")
            require(variable(body, "MODE", "[a-z]+") == "auto") { "Libvio requires interactive browser verification" }
            val cookieName = variable(body, "POW", "__cdn_pow")
            val digest = MessageDigest.getInstance("SHA-256")
            val started = System.nanoTime()
            val nonce = (0 until 2_097_152).firstOrNull { nonce ->
                if (nonce % 1024 == 0) {
                    check(!Thread.currentThread().isInterrupted) { "Libvio request cancelled" }
                    require(System.nanoTime() - started < 10_000_000_000L) { "Libvio browser verification timed out" }
                }
                val hash = digest.digest((sig + nonce).toByteArray(Charsets.US_ASCII))
                (0 until diff.length).all { index ->
                    val b = hash[index / 2].toInt() and 255
                    if (index % 2 == 0) b ushr 4 == 0 else b and 15 == 0
                }
            } ?: error("Libvio browser verification exhausted its work limit")
            transport.setCookie(url, "$cookieName=${ts}_auto_${nonce}_$sig")
            body = transport.get(url, HEADERS)
        }
        require("var POW =" !in body && !body.contains("browser verification required", true) &&
            !body.contains("Access denied by geographic", true)) { "Libvio requires browser verification or a permitted network route" }
        return body
    }
    private fun variable(body: String, name: String, pattern: String): String =
        Regex("""\b$name\s*=\s*["]($pattern)["]""").find(body)?.groupValues?.get(1)
            ?: error("Unsupported Libvio browser verification page")
    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36"
        val HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
            "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
            "Sec-CH-UA" to "\"Not=A?Brand\";v=\"99\", \"Google Chrome\";v=\"151\", \"Chromium\";v=\"151\"",
            "Sec-CH-UA-Mobile" to "?0", "Sec-CH-UA-Platform" to "\"Windows\"",
            "Sec-Fetch-Dest" to "document", "Sec-Fetch-Mode" to "navigate", "Sec-Fetch-Site" to "none",
            "Sec-Fetch-User" to "?1", "Upgrade-Insecure-Requests" to "1", "priority" to "u=0, i",
        )
    }
}
