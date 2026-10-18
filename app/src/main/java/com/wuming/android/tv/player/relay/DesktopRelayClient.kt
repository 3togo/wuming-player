package com.wuming.android.tv.player.relay

import android.os.Looper
import com.wuming.android.tv.App
import com.wuming.android.tv.bean.Result
import com.wuming.android.tv.player.engine.PlaySpec
import com.wuming.android.tv.setting.PlayerSettings
import com.wuming.tv.core.playback.relay.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.TimeUnit
import com.wuming.android.tv.utils.Task

/** Android persistence and transport port for the shared relay protocol. */
object DesktopRelayClient {
    // Separate private preferences: pairing secrets are never part of exported app settings.
    private val prefs get() = App.get().getSharedPreferences("desktop_relay", android.content.Context.MODE_PRIVATE)
    private val http by lazy { OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dns(Dns.SYSTEM)
        .followRedirects(false).followSslRedirects(false).connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS).callTimeout(9, TimeUnit.SECONDS).build() }
    private val memory = RelayRouteMemory()
    @Volatile private var cached: DiscoveredRelay? = null
    @Volatile private var checkedAt = 0L
    @Volatile private var cooldown = 0L
    val enabled get() = prefs.getBoolean("enabled", false)
    val paired get() = LanRelayProtocol.validToken(prefs.getString("token", "").orEmpty())
    @Synchronized fun clientId(): String = prefs.getString("clientId", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("clientId", it).apply()
    }
    fun setEnabled(value: Boolean) { prefs.edit().putBoolean("enabled", value).apply(); cached = null; cooldown = 0 }
    fun forget() { prefs.edit().remove("token").remove("id").putBoolean("enabled", false).apply(); cached = null }
    fun discover(): List<DiscoveredRelay> = LanRelayDiscovery.find(clientId())
    private fun post(endpoint: String, path: String, payload: Any, token: String? = null, request: RelayRequest? = null): Map<*, *> {
        val uri = java.net.URI(endpoint)
        require(uri.scheme == "http" && LanRelayProtocol.local(InetAddress.getByName(uri.host)))
        val builder = Request.Builder().url(endpoint + LanRelayProtocol.ROOT + path)
            .post(App.gson().toJson(payload).toRequestBody("application/json".toMediaType()))
        token?.let { builder.header("Authorization", "Bearer $it") }
        val call = http.newCall(builder.build())
        request?.attach(call)
        return call.execute().use { response ->
            check(response.isSuccessful)
            val body = response.body ?: error("Empty relay response")
            val bytes = body.byteStream().readBytesLimited()
            App.gson().fromJson(String(bytes), Map::class.java)
        }
    }
    private fun java.io.InputStream.readBytesLimited(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(2048)
        while (true) { val count = read(buffer); if (count < 0) break
            require(output.size() + count <= LanRelayProtocol.MAX_BODY); output.write(buffer, 0, count) }
        return output.toByteArray()
    }
    fun pair(relay: DiscoveredRelay, code: String) {
        require(code.matches(Regex("[0-9]{6}")))
        val response = post(relay.address, "/pair", mapOf("clientId" to clientId(), "code" to code))
        val token = response["token"]?.toString().orEmpty()
        require(response["id"] == relay.service.id && LanRelayProtocol.validToken(token))
        prefs.edit().putString("id", relay.service.id).putString("token", token).putBoolean("enabled", true).apply()
        cached = null; cooldown = 0
    }
    private fun endpoint(): DiscoveredRelay? {
        val now = System.currentTimeMillis()
        cached?.takeIf { now - checkedAt < 30000 }?.let { return it }
        val token = prefs.getString("token", "").orEmpty()
        val id = prefs.getString("id", "")
        if (!LanRelayProtocol.validToken(token)) return null
        return discover().firstOrNull { it.service.id == id && LanRelayProtocol.equal(it.service.proof,
            LanRelayProtocol.proof(token, it.service.nonce, it.service.id, it.service.port)) }?.also {
            cached = it; checkedAt = now
        }
    }
    /** Cancels the actual preparation call when playback changes or direct playback recovers. */
    class RelayRequest : AutoCloseable {
        private var closed = false
        private var call: Call? = null
        @Synchronized fun attach(value: Call) {
            if (closed || Thread.currentThread().isInterrupted) {
                value.cancel()
                throw java.io.InterruptedIOException("Relay request cancelled")
            }
            call = value
        }
        @Synchronized fun isClosed(): Boolean = closed || Thread.currentThread().isInterrupted
        @Synchronized override fun close() { closed = true; call?.cancel(); call = null }
    }

    fun available(): Boolean = enabled && paired && System.currentTimeMillis() >= cooldown

    fun original(route: String): RelayOriginal? = memory.original(route)

    fun request(original: RelayOriginal, cancellation: RelayRequest): String? {
        if (Looper.myLooper() == Looper.getMainLooper() || !available() || cancellation.isClosed()) return null
        if (!original.url.startsWith("http://", true) && !original.url.startsWith("https://", true)) return null
        var stage = "eligibility"
        return try {
            val origin = LanRelayProtocol.upstream(original.url)
            if ((origin.host.matches(Regex("[0-9.]+")) || origin.host.contains(':')) &&
                LanRelayProtocol.local(InetAddress.getByName(origin.host))) return null
            stage = "discovery"
            val relay = endpoint() ?: run {
                cooldown = System.currentTimeMillis() + 30000
                return null
            }
            if (cancellation.isClosed()) return null
            val headers = original.headers.toMutableMap()
            val configuredAgent = PlayerSettings.getUa().orEmpty()
            if (configuredAgent.isNotEmpty() && headers.keys.none { it.equals("User-Agent", true) }) headers["User-Agent"] = configuredAgent
            stage = "session"
            val response = post(relay.address, "/session", RelaySessionRequest(original.url, headers),
                prefs.getString("token", ""), cancellation)
            val route = response["url"]?.toString().orEmpty()
            require(route.startsWith(relay.address + LanRelayProtocol.ROOT + "/media/"))
            memory.remember(route, original)
            if (cancellation.isClosed() || !enabled) { release(route); null } else route
        } catch (failure: Exception) {
            if (!cancellation.isClosed()) {
                android.util.Log.d("DesktopRelay", "$stage failed: ${failure.javaClass.simpleName}; retaining direct playback")
                cached = null
                cooldown = System.currentTimeMillis() + 60000
            }
            null
        }
    }

    /** Explicit routing retained for the opt-in transport comparison fixture. */
    fun route(result: Result): Result {
        if (result.drm != null) return result
        val cancellation = RelayRequest()
        val route = try { request(RelayOriginal(result.realUrl, result.header, result.format), cancellation) }
                    finally { cancellation.close() }
        if (route == null) return result
        val snapshot = App.gson().toJsonTree(result).asJsonObject
        snapshot.remove("header"); snapshot.remove("playUrl")
        return App.gson().fromJson(snapshot, Result::class.java).also {
            it.setUrl(route); it.setFormat("application/x-mpegURL")
        }
    }

    fun release(route: String) {
        if (memory.fallback(route) == null) return
        closeSession(route)
    }

    private fun closeSession(route: String) {
        val token = prefs.getString("token", "").orEmpty()
        if (!LanRelayProtocol.validToken(token)) return
        val endpoint = route.substringBefore(LanRelayProtocol.ROOT)
        val session = route.substringAfter("/media/").substringBefore('/')
        if (!LanRelayProtocol.validToken(session)) return
        Task.execute {
            runCatching {
                val uri = java.net.URI(endpoint)
                require(uri.scheme == "http" && LanRelayProtocol.local(InetAddress.getByName(uri.host)))
                http.newCall(Request.Builder().url(endpoint + LanRelayProtocol.ROOT + "/session/" + session)
                    .header("Authorization", "Bearer $token").delete().build()).execute().use { }
            }
        }
    }
    fun fallback(spec: PlaySpec?): Boolean {
        val original = spec?.getUrl()?.let(memory::fallback) ?: return false
        spec.getUrl()?.let(::closeSession)
        spec.setUrl(original.url); spec.setHeaders(original.headers); spec.setFormat(original.format)
        cached = null; cooldown = System.currentTimeMillis() + 60000
        return true
    }
}
