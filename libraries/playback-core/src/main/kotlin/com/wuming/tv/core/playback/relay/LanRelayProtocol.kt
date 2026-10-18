package com.wuming.tv.core.playback.relay

import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object LanRelayProtocol {
    const val DISCOVERY_PORT = 39282
    const val DISCOVER = "WUMING_RELAY_DISCOVER_V1"
    const val ROOT = "/relay/v1"
    const val MAX_BODY = 16384
    fun secret(): String = ByteArray(32).also(SecureRandom()::nextBytes)
        .joinToString("") { "%02x".format(it) }
    fun validId(value: String) = value.matches(Regex("[a-zA-Z0-9-]{8,64}"))
    fun validToken(value: String) = value.matches(Regex("[0-9a-f]{64}"))
    fun local(address: InetAddress): Boolean = address.isSiteLocalAddress || address.isLoopbackAddress ||
        address.isLinkLocalAddress || (address.address.size == 16 && address.address[0].toInt() and 0xfe == 0xfc)
    fun upstream(value: String): URI {
        require(value.length in 1..8192 && value.none(Character::isISOControl))
        val uri = URI(value)
        require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null)
        return uri
    }
    fun proof(token: String, nonce: String, id: String, port: Int): String {
        require(validToken(token) && validToken(nonce) && validId(id) && port in 1..65535)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(Charsets.US_ASCII), "HmacSHA256"))
        return mac.doFinal("$nonce|$id|$port".toByteArray()).joinToString("") { "%02x".format(it) }
    }
    fun equal(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    fun scopedHeaders(headers: Map<String, String>, origin: URI, target: URI): Map<String, String> {
        fun effectivePort(uri: URI) = if (uri.port >= 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
        val sameOrigin = origin.scheme.equals(target.scheme, true) && origin.host.equals(target.host, true) &&
            effectivePort(origin) == effectivePort(target)
        val forbidden = setOf("host", "connection", "content-length", "transfer-encoding", "proxy-authorization", "range")
        require(headers.size <= 32 && headers.entries.sumOf { it.key.length + it.value.length } <= 8192)
        return headers.filter { (key, value) ->
            require(key.matches(Regex("[A-Za-z0-9-]+")) && value.none { it == '\r' || it == '\n' })
            key.lowercase() !in forbidden && (sameOrigin || key.equals("User-Agent", true) || key.equals("Referer", true))
        }
    }
}

data class RelayAdvertisement(val id: String, val name: String, val port: Int, val nonce: String, val proof: String = "")
data class RelaySessionRequest(val url: String, val headers: Map<String, String> = emptyMap())

/** Pairing is explicitly opened by the host user, expires, and closes after five failed claims. */
class RelayPairingGate(
    initial: Map<String, String> = emptyMap(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val tokens = initial.filter { LanRelayProtocol.validId(it.key) && LanRelayProtocol.validToken(it.value) }.toMutableMap()
    private var code = ""
    private var deadline = 0L
    private var attempts = 0
    @Synchronized fun open(): String {
        code = "%06d".format(SecureRandom().nextInt(1000000)); deadline = clock() + 120000; attempts = 0
        return code
    }
    @Synchronized fun claim(clientId: String, supplied: String): String? {
        if (clock() >= deadline || code.isEmpty() || attempts >= 5 || !LanRelayProtocol.validId(clientId)) return null
        attempts++
        if (!LanRelayProtocol.equal(code, supplied)) return null
        code = ""; deadline = 0
        return LanRelayProtocol.secret().also { tokens[clientId] = it }
    }
    @Synchronized fun token(clientId: String): String? = tokens[clientId]
    @Synchronized fun authorized(value: String): Boolean = LanRelayProtocol.validToken(value) && tokens.values.any { LanRelayProtocol.equal(it, value) }
    @Synchronized fun snapshot(): Map<String, String> = tokens.toMap()
    @Synchronized fun clear() { tokens.clear(); code = ""; deadline = 0 }
}

data class RelayOriginal(val url: String, val headers: Map<String, String>, val format: String?)
class RelayRouteMemory(private val clock: () -> Long = System::currentTimeMillis) {
    private val routes = LinkedHashMap<String, Pair<Long, RelayOriginal>>()
    @Synchronized fun remember(relay: String, original: RelayOriginal) {
        routes.entries.removeIf { clock() - it.value.first > 3600000 }
        if (routes.size >= 64) routes.remove(routes.keys.first())
        routes[relay] = clock() to original.copy(headers = original.headers.toMap())
    }
    @Synchronized fun original(relay: String): RelayOriginal? = routes[relay]?.second
    @Synchronized fun fallback(relay: String): RelayOriginal? = routes.remove(relay)?.second
}
