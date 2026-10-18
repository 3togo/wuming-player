package com.wuming.tv.desktop.server

import com.google.gson.Gson
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.wuming.tv.core.playback.hls.HlsTransformContext
import com.wuming.tv.core.playback.hls.UriRewriteTransform
import com.wuming.tv.core.playback.relay.*
import com.wuming.tv.core.runtime.js.EnvironmentProxySelector
import com.wuming.tv.desktop.player.RelayExecutors
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class DesktopLanRelaySettings(private val file: Path = Path.of(System.getProperty("user.home"), ".config", "tvbox-desktop", "lan-relay.properties")) {
    private val values = Properties().apply { if (Files.exists(file)) Files.newInputStream(file).use(::load) }
    val id: String = values.getProperty("id")?.takeIf(LanRelayProtocol::validId) ?: UUID.randomUUID().toString()
    var enabled: Boolean = values.getProperty("enabled") == "true"
    fun tokens(): Map<String, String> = values.stringPropertyNames().filter { it.startsWith("peer.") }
        .associate { it.removePrefix("peer.") to values.getProperty(it) }
    @Synchronized fun save(tokens: Map<String, String>) {
        values.clear(); values.setProperty("id", id); values.setProperty("enabled", enabled.toString())
        tokens.forEach { (key, value) -> values.setProperty("peer.$key", value) }
        Files.createDirectories(file.parent)
        val temp = Files.createTempFile(file.parent, "relay-", ".tmp")
        try {
            runCatching { Files.setPosixFilePermissions(temp, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)) }
            Files.newOutputStream(temp).use { values.store(it, "Private LAN relay pairing") }
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } finally { Files.deleteIfExists(temp) }
    }
}

/** Opt-in LAN server. The service shares no catalog and accepts only paired stream requests. */
class DesktopLanRelay(
    val settings: DesktopLanRelaySettings = DesktopLanRelaySettings(),
    private val client: OkHttpClient = OkHttpClient.Builder()
        // Match the measured fast route: reusable HTTP/1.1 connections through the desktop proxy.
        .proxySelector(EnvironmentProxySelector()).protocols(listOf(Protocol.HTTP_1_1)).followRedirects(false).followSslRedirects(false)
        .dns { host -> Dns.SYSTEM.lookup(host).also { addresses -> require(addresses.none { LanRelayProtocol.local(it) || it.isAnyLocalAddress }) } }
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build(),
) : AutoCloseable {
    private val gson = Gson()
    private val gate = RelayPairingGate(settings.tokens())
    @Volatile private var server: HttpServer? = null
    private var discovery: DatagramSocket? = null
    private var discoveryReplies: DatagramSocket? = null
    private var workers: java.util.concurrent.ExecutorService? = null
    private val sessions = ConcurrentHashMap<String, Session>()
    private val preparing = AtomicInteger()
    private val sessionNumbers = AtomicLong()
    private val deliveredBytes = AtomicLong()
    private val failures = AtomicLong()
    @Volatile var address = ""
        private set
    val running: Boolean get() = server != null
    val pairedDevices: Int get() = gate.snapshot().size
    fun monitorSnapshot(): RelayMonitorSnapshot {
        val now = System.currentTimeMillis()
        return RelayMonitorSnapshot(running, pairedDevices, preparing.get(), deliveredBytes.get(), failures.get(),
            sessions.values.filter { now - it.lastUsed <= 600000 }.map { session ->
                RelaySessionSnapshot(session.number, session.peer, session.activeRequests.get(), session.requests.get(),
                    session.deliveredBytes.get(), session.failures.get(), (now - session.lastUsed).coerceAtLeast(0))
            }.sortedBy { it.number })
    }
    fun pairingCode(): String { check(running); return gate.open() }
    @Synchronized fun forgetDevices() { gate.clear(); sessions.clear(); settings.save(gate.snapshot()) }

    @Synchronized fun enable(bind: InetAddress = lanAddress()) {
        if (running) return
        require(LanRelayProtocol.local(bind))
        val http = HttpServer.create(InetSocketAddress(bind, 0), 16)
        val pool = RelayExecutors.create("desktop-lan-relay", 6)
        val udp = DatagramSocket(null)
        val replies = DatagramSocket(null)
        try {
            udp.reuseAddress = false; udp.bind(InetSocketAddress(LanRelayProtocol.DISCOVERY_PORT))
            // Discovery derives the HTTP endpoint from the reply's source address. A
            // wildcard sender can choose another interface on a multihomed desktop.
            replies.bind(InetSocketAddress(bind, 0))
            http.executor = pool; http.createContext(LanRelayProtocol.ROOT, ::handle)
            address = "http://${bind.hostAddress}:${http.address.port}"
            server = http; workers = pool; discovery = udp; discoveryReplies = replies
            http.start(); settings.enabled = true; settings.save(gate.snapshot())
            Thread({ advertise(udp, replies, http.address.port) }, "desktop-relay-discovery").apply { isDaemon = true; start() }
        } catch (failure: Exception) {
            http.stop(0); udp.close(); replies.close(); pool.shutdownNow(); server = null; workers = null; discovery = null; discoveryReplies = null
            throw failure
        }
    }
    @Synchronized fun disable() { settings.enabled = false; close(); settings.save(gate.snapshot()) }
    @Synchronized override fun close() {
        discovery?.close(); discovery = null; server?.stop(0); server = null
        discoveryReplies?.close(); discoveryReplies = null
        workers?.shutdownNow(); workers = null; sessions.clear(); address = ""
    }
    private fun advertise(socket: DatagramSocket, replies: DatagramSocket, port: Int) {
        while (!socket.isClosed) try {
            val packet = DatagramPacket(ByteArray(1024), 1024); socket.receive(packet)
            if (!LanRelayProtocol.local(packet.address)) continue
            val parts = String(packet.data, 0, packet.length).split('|')
            if (parts.size != 3 || parts[0] != LanRelayProtocol.DISCOVER || !LanRelayProtocol.validToken(parts[1]) || !LanRelayProtocol.validId(parts[2])) continue
            val proof = gate.token(parts[2])?.let { LanRelayProtocol.proof(it, parts[1], settings.id, port) }.orEmpty()
            val bytes = gson.toJson(RelayAdvertisement(settings.id, "無名 Desktop", port, parts[1], proof)).toByteArray()
            replies.send(DatagramPacket(bytes, bytes.size, packet.address, packet.port))
        } catch (_: Exception) { if (socket.isClosed) break }
    }
    private fun body(exchange: HttpExchange): String {
        val bytes = exchange.requestBody.use { it.readNBytes(LanRelayProtocol.MAX_BODY + 1) }
        require(bytes.size <= LanRelayProtocol.MAX_BODY)
        return String(bytes, Charsets.UTF_8)
    }
    private fun json(exchange: HttpExchange, status: Int, value: Any) {
        val bytes = gson.toJson(value).toByteArray()
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
    }
    private fun handle(exchange: HttpExchange) {
        val serving = server
        var preparation = false
        var mediaSession: Session? = null
        try {
            if (!LanRelayProtocol.local(exchange.remoteAddress.address)) { json(exchange, 403, mapOf("error" to "LAN only")); return }
            val path = exchange.requestURI.path.removePrefix(LanRelayProtocol.ROOT)
            if (path == "/pair" && exchange.requestMethod == "POST") {
                val values = gson.fromJson(body(exchange), Map::class.java)
                val token = gate.claim(values["clientId"]?.toString().orEmpty(), values["code"]?.toString().orEmpty())
                if (token == null) json(exchange, 403, mapOf("error" to "Pairing code expired or invalid"))
                else { settings.save(gate.snapshot()); json(exchange, 200, mapOf("token" to token, "id" to settings.id)) }
                return
            }
            if (path == "/session" && exchange.requestMethod == "POST") {
                val auth = exchange.requestHeaders.getFirst("Authorization").orEmpty().removePrefix("Bearer ")
                if (!gate.authorized(auth)) { json(exchange, 401, mapOf("error" to "Pairing required")); return }
                preparation = true; preparing.incrementAndGet()
                val request = gson.fromJson(body(exchange), RelaySessionRequest::class.java)
                val origin = LanRelayProtocol.upstream(request.url)
                validateTarget(origin)
                val headers = LanRelayProtocol.scopedHeaders(request.headers, origin, origin)
                val session = Session(origin, headers, exchange.remoteAddress.address.hostAddress, auth, client, sessionNumbers.incrementAndGet())
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RelayAssistancePolicy.PREPARE_BUDGET_MILLIS)
                open(origin, session.headers, session.transport, deadline).use { response ->
                    require(response.isSuccessful)
                    val bytes = response.body!!.byteStream().readNBytes(1048577)
                    require(bytes.size <= 1048576 && bytes.toString(Charsets.UTF_8).startsWith("#EXTM3U"))
                    session.prepared(response.request.url.toString(), bytes.toString(Charsets.UTF_8))
                }
                val id = LanRelayProtocol.secret()
                val admitted = synchronized(this) {
                    check(serving != null && server === serving && gate.authorized(auth)) { "Relay stopped or pairing revoked" }
                    sessions.entries.removeIf { System.currentTimeMillis() - it.value.lastUsed > 600000 }
                    if (sessions.size >= 32) false else { sessions[id] = session; true }
                }
                if (!admitted) { failures.incrementAndGet(); json(exchange, 503, mapOf("error" to "Relay busy")); return }
                val resource = session.register(origin.toString())
                json(exchange, 200, mapOf("url" to "$address${LanRelayProtocol.ROOT}/media/$id/$resource/index.m3u8"))
                return
            }
            if (path.startsWith("/session/") && exchange.requestMethod == "DELETE") {
                val auth = exchange.requestHeaders.getFirst("Authorization").orEmpty().removePrefix("Bearer ")
                if (!gate.authorized(auth)) { json(exchange, 401, mapOf("error" to "Pairing required")); return }
                val id = path.removePrefix("/session/")
                val session = sessions[id]
                if (session != null && session.peer == exchange.remoteAddress.address.hostAddress &&
                    LanRelayProtocol.equal(session.ownerToken, auth)) sessions.remove(id, session)
                json(exchange, 200, mapOf("released" to true))
                return
            }
            if (exchange.requestMethod != "GET" || !path.startsWith("/media/")) { json(exchange, 404, mapOf("error" to "Not found")); return }
            val parts = path.split('/')
            val session = parts.getOrNull(2)?.let(sessions::get)
            if (session == null || session.peer != exchange.remoteAddress.address.hostAddress || System.currentTimeMillis() - session.lastUsed > 600000) {
                json(exchange, 401, mapOf("error" to "Session expired")); return
            }
            session.lastUsed = System.currentTimeMillis()
            mediaSession = session
            session.activeRequests.incrementAndGet(); session.requests.incrementAndGet()
            val target = parts.getOrNull(3)?.let(session::resolve)?.let(LanRelayProtocol::upstream)
                ?: throw IllegalArgumentException("Unknown resource")
            val headers = LanRelayProtocol.scopedHeaders(session.headers, session.origin, target).toMutableMap()
            exchange.requestHeaders.getFirst("Range")?.takeIf { it.matches(Regex("bytes=\\d+-\\d*")) }?.let { headers["Range"] = it }
            val prepared = session.takePrepared(target)
            if (prepared != null) {
                servePlaylist(exchange, session, parts[2], prepared.first, prepared.second)
                return
            }
            open(target, headers, session.transport).use { response ->
                val responseBody = response.body ?: error("No response")
                if (!response.isSuccessful) {
                    session.failures.incrementAndGet(); failures.incrementAndGet()
                    json(exchange, 502, mapOf("error" to "Upstream unavailable")); return
                }
                val source = responseBody.source()
                val playlist = source.peek().readByteArray(minOf(7L, responseBody.contentLength().takeIf { it >= 0 } ?: 7)).toString(Charsets.UTF_8).startsWith("#EXTM3U")
                if (playlist) {
                    val bytes = source.inputStream().readNBytes(1048577)
                    require(bytes.size <= 1048576)
                    servePlaylist(exchange, session, parts[2], response.request.url.toString(), String(bytes, Charsets.UTF_8))
                } else {
                    exchange.responseHeaders.set("Content-Type", response.header("Content-Type") ?: "application/octet-stream")
                    response.header("Content-Range")?.let { exchange.responseHeaders.set("Content-Range", it) }
                    exchange.sendResponseHeaders(response.code, responseBody.contentLength().takeIf { it > 0 } ?: 0)
                    measuredOutput(exchange, session).use { output -> responseBody.byteStream().copyTo(output, 65536) }
                }
            }
        } catch (failure: Exception) {
            if (preparation || mediaSession != null) failures.incrementAndGet()
            mediaSession?.failures?.incrementAndGet()
            java.util.logging.Logger.getLogger("DesktopLanRelay").fine("Relay request failed: ${failure.javaClass.simpleName}")
            runCatching { json(exchange, 502, mapOf("error" to "Relay unavailable; use direct playback")) }
        }
        finally {
            if (preparation) preparing.decrementAndGet()
            mediaSession?.let { it.activeRequests.decrementAndGet(); it.lastUsed = System.currentTimeMillis() }
            exchange.close()
        }
    }
    private fun measuredOutput(exchange: HttpExchange, session: Session): java.io.OutputStream =
        object : java.io.FilterOutputStream(exchange.responseBody) {
            override fun write(value: Int) { out.write(value); count(1) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { out.write(bytes, offset, length); count(length) }
            private fun count(length: Int) { session.deliveredBytes.addAndGet(length.toLong()); deliveredBytes.addAndGet(length.toLong()) }
        }
    private fun servePlaylist(exchange: HttpExchange, session: Session, id: String, base: String, playlist: String) {
        val content = UriRewriteTransform().apply(playlist, HlsTransformContext(base, { child ->
            "$address${LanRelayProtocol.ROOT}/media/$id/${session.register(child)}/resource.ts"
        }, emptyMap())).content.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/vnd.apple.mpegurl")
        exchange.sendResponseHeaders(200, content.size.toLong())
        measuredOutput(exchange, session).use { it.write(content) }
    }

    private fun validateTarget(uri: URI) {
        LanRelayProtocol.upstream(uri.toString())
        require(!uri.host.equals("localhost", true) && !uri.host.endsWith(".localhost", true))
        if (uri.host.matches(Regex("[0-9.]+")) || uri.host.contains(':')) {
            val address = InetAddress.getByName(uri.host)
            require(!LanRelayProtocol.local(address) && !address.isAnyLocalAddress && !address.isMulticastAddress)
        }
    }
    private fun open(initial: URI, headers: Map<String, String>, transport: OkHttpClient, deadline: Long? = null): Response {
        var uri = initial
        repeat(5) {
            validateTarget(uri)
            val request = Request.Builder().url(uri.toString()).header("User-Agent", "Mozilla/5.0")
            LanRelayProtocol.scopedHeaders(headers, initial, uri).forEach { (key, value) -> request.header(key, value) }
            headers["Range"]?.let { request.header("Range", it) }
            val bounded = if (deadline == null) transport else {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw java.io.InterruptedIOException("Relay preparation timed out")
                val configured = transport.callTimeoutMillis.toLong().takeIf { it > 0 }?.let(TimeUnit.MILLISECONDS::toNanos)
                transport.newBuilder().callTimeout(minOf(remaining, configured ?: remaining), TimeUnit.NANOSECONDS).build()
            }
            val response = bounded.newCall(request.build()).execute()
            if (!response.isRedirect) return response
            val location = response.header("Location"); response.close()
            require(location != null); uri = uri.resolve(location)
        }
        error("Too many redirects")
    }
    private class Session(val origin: URI, supplied: Map<String, String>, val peer: String, val ownerToken: String, base: OkHttpClient, val number: Long) {
        val activeRequests = AtomicInteger()
        val requests = AtomicLong()
        val deliveredBytes = AtomicLong()
        val failures = AtomicLong()
        private val cookies = RelayCookieJar(origin.toString().toHttpUrl(), supplied)
        val headers = supplied.filterKeys { !it.equals("Cookie", true) }
        val transport = base.newBuilder().cookieJar(cookies).build()
        private var initialPlaylist: Pair<String, String>? = null
        private var preparedAt = 0L
        @Synchronized fun prepared(base: String, playlist: String) { initialPlaylist = base to playlist; preparedAt = System.nanoTime() }
        @Synchronized fun takePrepared(target: URI): Pair<String, String>? {
            if (target != origin) return null
            val result = initialPlaylist.takeIf { System.nanoTime() - preparedAt <= TimeUnit.SECONDS.toNanos(2) }
            initialPlaylist = null
            return result
        }
        private val resources = LinkedHashMap<String, String>(32, 0.75f, true)
        @Volatile var lastUsed = System.currentTimeMillis()
        @Synchronized fun resolve(id: String): String? = resources[id]
        @Synchronized fun register(url: String): String {
            LanRelayProtocol.upstream(url)
            resources.entries.firstOrNull { it.value == url }?.key?.let { id -> resources[id]; return id }
            // Retain the entry playlist and recently referenced keys/variants during long live sessions.
            if (resources.size >= 2048) resources.entries.firstOrNull { it.value != origin.toString() }?.key?.let(resources::remove)
            return UUID.randomUUID().toString().also { resources[it] = url }
        }
    }
    companion object {
        fun lanAddress(): InetAddress = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual && listOf("docker", "veth", "br-", "virbr", "tailscale", "tun", "tap").none(it.name::startsWith) }
            .flatMap { it.inetAddresses.toList() }.first { it is Inet4Address && it.isSiteLocalAddress }
    }
}
