package com.wuming.tv.core.upnp

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.net.URI
import java.security.SecureRandom
import java.util.Locale

/** Pairing and authorization workflow, independent of HTTP server and preference frameworks. */
class RemoteAccessPolicy(private val store: Store, private val tvRole: () -> Boolean) {
    interface Store {
        fun read(key: String): String?
        fun write(key: String, value: String)
    }
    private val pairing = QrPairingSession()
    private val gson = Gson()
    private val tokenMap = object : TypeToken<HashMap<String, String>>() {}.type
    private val random = SecureRandom()
    fun secureWebAddress(address: String, tab: Int) = "$address?tab=$tab#pair=${pairing.code()}"
    fun openPairingWindow() { if (tvRole()) pairing.open() }
    fun closePairingWindow() = pairing.close()
    fun claimPairing(code: String?, peer: String): String =
        if (tvRole() && code != null && pairing.claim(code, peer)) serverToken() else ""
    @Synchronized fun rememberPairing(address: String, token: String) {
        val origin = origin(address)
        if (origin.isEmpty() || !isValidToken(token)) return
        val tokens = pairedTokens().toMutableMap()
        tokens[origin] = token
        store.write(PAIRED_TOKENS, gson.toJson(tokens))
    }
    @Synchronized fun rotateServerToken() {
        store.write(SERVER_TOKEN, randomToken())
        openPairingWindow()
    }
    fun authorizationFor(url: String): String? = pairedTokens()[origin(url)]
        .takeIf(::isValidToken)?.let { BEARER + it }
    fun allow(address: String?, url: String, castMediaAuthorized: Boolean, authorization: String?): Boolean {
        if (isLoopback(address) || castMediaAuthorized || !isProtected(url)) return true
        return tvRole() && authorization != null && authorization == BEARER + serverToken()
    }
    @Synchronized private fun serverToken(): String {
        val token = store.read(SERVER_TOKEN)
        if (isValidToken(token)) return token!!
        return randomToken().also { store.write(SERVER_TOKEN, it) }
    }
    private fun pairedTokens(): Map<String, String> = try {
        val result: Map<String, String>? = gson.fromJson(store.read(PAIRED_TOKENS), tokenMap)
        if (result == null) HashMap() else HashMap(result)
    } catch (error: Exception) { HashMap() }
    private fun randomToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    companion object {
        private const val BEARER = "Bearer "
        private const val SERVER_TOKEN = "remote_server_token"
        private const val PAIRED_TOKENS = "remote_paired_tokens"
        @JvmStatic fun isProtected(url: String): Boolean = listOf(
            "/action", "/desktop/", "/cache", "/media", "/file", "/upload", "/packages/",
            "/newFolder", "/delFolder", "/delFile"
        ).any(url::startsWith)
        @JvmStatic fun isSecretPreference(key: String) = key == SERVER_TOKEN || key == PAIRED_TOKENS
        private fun isValidToken(token: String?): Boolean = token != null && token.matches("[0-9a-f]{64}".toRegex())
        private fun isLoopback(address: String?): Boolean = address?.lowercase(Locale.ROOT) in
            listOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1")
        private fun origin(address: String): String = try {
            val uri = URI.create(address)
            if (uri.scheme == null || uri.host == null) "" else
                uri.scheme.lowercase(Locale.ROOT) + "://" + uri.host.lowercase(Locale.ROOT) +
                    if (uri.port < 0) "" else ":${uri.port}"
        } catch (error: Exception) { "" }
    }
}
