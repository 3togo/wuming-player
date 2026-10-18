package com.wuming.tv.desktop.cache

import com.wuming.tv.core.base.AtomicFilePublisher
import com.wuming.tv.core.cache.BoundedCachePolicy
import com.wuming.tv.core.base.Digests
import com.wuming.tv.desktop.net.DesktopHttpStatusException
import com.wuming.tv.desktop.net.DesktopResourceLoader
import com.wuming.tv.desktop.net.DesktopResourceResponse
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import okhttp3.CacheControl
import okhttp3.Headers

internal enum class ResourceOrigin { NETWORK, FRESH, REVALIDATED, STALE }
internal data class CachedResource(val bytes: ByteArray, val origin: ResourceOrigin, val cacheable: Boolean = true)

/** Opt-in cache for public resources only. A validator runs before any bytes enter the store. */
internal class ResourceDiskCache(
    private val directory: Path,
    private val freshAge: Duration,
    private val staleAge: Duration,
    private val maximumEntries: Int,
    private val maximumBytes: Long,
    private val maximumEntryBytes: Int,
    private val clock: Clock = Clock.systemUTC(),
    private val fetch: (String, Map<String, String>) -> DesktopResourceResponse =
        DesktopResourceLoader(maximumBytes = maximumEntryBytes)::loadResponse,
) {
    // One shared instance per resource family; serialization also coalesces successful concurrent misses.
    @Synchronized
    fun load(
        location: String,
        headers: Map<String, String> = emptyMap(),
        forceRefresh: Boolean = false,
        validate: (ByteArray) -> Boolean,
    ): CachedResource {
        if (runCatching { URI(location).scheme?.lowercase() }.getOrNull() !in setOf("http", "https")) {
            return CachedResource(fetch(location, headers).bytes.also { validate(it) }, ResourceOrigin.NETWORK)
        }
        val requestHeaders = headers.mapKeys { it.key.lowercase() }.toSortedMap()
        val key = digest(buildString {
            append(location.length).append(':').append(location)
            requestHeaders.forEach { (name, value) -> append(name.length).append(':').append(name).append(value.length).append(':').append(value) }
        })
        val target = directory.resolve("$key.bin")
        val privateRequest = requestHeaders.keys.any { it in setOf("authorization", "cookie") } ||
            controls(requestHeaders).noStore
        var old = if (privateRequest) null else read(target)?.takeIf { entry ->
            runCatching { validate(entry.bytes) }.getOrDefault(false)
        }
        if (old == null) remove(target)
        if (!forceRefresh && !controls(requestHeaders).noCache && controls(requestHeaders).maxAgeSeconds != 0 && old != null && fresh(old)) {
            access(target)
            return observed(old.bytes, ResourceOrigin.FRESH)
        }
        val conditional = headers.toMutableMap()
        if (old != null) {
            old.headers["etag"]?.let { conditional["If-None-Match"] = it }
                ?: old.headers["last-modified"]?.let { conditional["If-Modified-Since"] = it }
        }
        try {
            var response = fetch(location, conditional)
            if (response.status == 304 && old == null) response = fetch(location, headers)
            require(response.status != 304 || old != null) { "HTTP 304 has no valid cached body." }
            val revalidated = response.status == 304
            val bytes = if (revalidated) old!!.bytes else response.bytes
            require(bytes.size <= maximumEntryBytes) { "Cached resource exceeds the size limit." }
            val responseHeaders = response.headers.mapKeys { it.key.lowercase() }
            val merged = if (revalidated) (old!!.headers - "age" - "date") + responseHeaders else responseHeaders
            if (privateRequest || !storable(merged)) { remove(target); old = null }
            val valid = validate(bytes)
            val entry = Entry(clock.millis(), merged, bytes)
            if (!privateRequest && valid && storable(merged) && response.status in setOf(200, 304)) write(target, entry)
            return observed(bytes, if (revalidated) ResourceOrigin.REVALIDATED else ResourceOrigin.NETWORK)
                .copy(cacheable = !privateRequest && valid && storable(merged))
        } catch (failure: Exception) {
            val transient = failure is IOException || (failure is DesktopHttpStatusException && failure.statusCode in 500..599)
            if (transient && !Thread.currentThread().isInterrupted && old != null && !controls(requestHeaders).noCache && staleAllowed(old)) {
                access(target)
                return observed(old.bytes, ResourceOrigin.STALE)
            }
            throw failure
        }
    }

    @Synchronized
    fun clear() {
        if (!Files.isDirectory(directory)) return
        Files.list(directory).use { files -> files.filter { it.fileName.toString().endsWith(".bin") || it.fileName.toString().endsWith(".alias") }.forEach(Files::deleteIfExists) }
    }

    /** Only used when a configuration navigation endpoint is unreachable. Contains no response body. */
    @Synchronized
    fun fallbackLocation(location: String): String? = runCatching {
        val path = directory.resolve("${digest(location)}.alias")
        require(Files.size(path) <= 16 * 1024)
        require(
            !BoundedCachePolicy.isExpired(
                Files.getLastModifiedTime(path).toMillis(),
                clock.millis(),
                staleAge.toMillis(),
            ),
        )
        Files.readString(path).also { value ->
            val uri = URI(value)
            require(uri.scheme in setOf("http", "https") && uri.userInfo == null)
        }
    }.getOrNull()

    @Synchronized
    fun rememberFallback(location: String, fallback: String) {
        if (location == fallback) return
        runCatching {
            require(fallback.toByteArray().size <= 16 * 1024)
            val target = directory.resolve("${digest(location)}.alias")
            AtomicFilePublisher.publish(target, ".resource-", ownerOnly = true) { Files.writeString(it, fallback) }
            access(target)
            val aliases = Files.list(directory).use { stream -> stream.filter { it.fileName.toString().endsWith(".alias") }
                .toList().sortedByDescending { Files.getLastModifiedTime(it).toMillis() } }
            aliases.drop(maximumEntries).forEach(::remove)
        }
    }

    private fun observed(bytes: ByteArray, origin: ResourceOrigin): CachedResource {
        java.util.logging.Logger.getLogger("tvbox.cache").info("resource=${directory.fileName} origin=$origin bytes=${bytes.size}")
        return CachedResource(bytes, origin)
    }

    private fun controls(headers: Map<String, String>): CacheControl = CacheControl.parse(
        Headers.Builder().apply { headers.forEach { (name, value) -> add(name, value) } }.build(),
    )

    private fun storable(headers: Map<String, String>): Boolean = !controls(headers).noStore &&
        headers["vary"]?.split(',')?.none { it.trim() == "*" } != false && !headers.containsKey("set-cookie")

    private fun fresh(entry: Entry): Boolean {
        val control = controls(entry.headers)
        if (control.noCache || !storable(entry.headers)) return false
        val ttl = when {
            control.maxAgeSeconds >= 0 -> minOf(freshAge.toMillis(), control.maxAgeSeconds.toLong() * 1000)
            entry.headers.containsKey("expires") -> minOf(freshAge.toMillis(),
                (date(entry.headers["expires"]) ?: 0) - (date(entry.headers["date"]) ?: entry.validated))
            else -> freshAge.toMillis()
        }
        val age = age(entry)
        return age != null && age < ttl
    }

    private fun age(entry: Entry): Long? {
        val elapsed = clock.millis() - entry.validated
        if (elapsed < 0) return null
        val initial = maxOf(
            entry.headers["age"]?.toLongOrNull()?.coerceIn(0, Long.MAX_VALUE / 1000)?.times(1000) ?: 0,
            (entry.validated - (date(entry.headers["date"]) ?: entry.validated)).coerceAtLeast(0),
        )
        return if (initial > Long.MAX_VALUE - elapsed) Long.MAX_VALUE else initial + elapsed
    }

    private fun staleAllowed(entry: Entry): Boolean {
        val control = controls(entry.headers)
        val age = age(entry) ?: return false
        return storable(entry.headers) && !control.noCache && !control.mustRevalidate &&
            age < staleAge.toMillis()
    }

    private fun read(path: Path): Entry? = runCatching {
        require(Files.size(path) <= maximumEntryBytes.toLong() + 128 * 1024)
        DataInputStream(Files.newInputStream(path).buffered()).use { input ->
            require(input.readInt() == VERSION)
            val validated = input.readLong()
            require(validated in 0..clock.millis())
            val count = input.readInt()
            require(count in 0..128)
            val headers = buildMap { repeat(count) { put(input.readUTF(), input.readUTF()) } }
            val size = input.readInt()
            require(size in 0..maximumEntryBytes)
            val expected = input.readUTF()
            val bytes = input.readNBytes(size)
            require(bytes.size == size && input.read() == -1 && digest(bytes) == expected)
            Entry(validated, headers, bytes)
        }
    }.getOrNull()

    private fun write(path: Path, entry: Entry) {
        runCatching {
            AtomicFilePublisher.publish(path, ".resource-", ownerOnly = true) { temp ->
                DataOutputStream(Files.newOutputStream(temp).buffered()).use { output ->
                    output.writeInt(VERSION)
                    output.writeLong(entry.validated)
                    output.writeInt(entry.headers.size)
                    entry.headers.forEach { (key, value) -> output.writeUTF(key); output.writeUTF(value) }
                    output.writeInt(entry.bytes.size)
                    output.writeUTF(digest(entry.bytes))
                    output.write(entry.bytes)
                }
            }
            access(path)
            trim()
        }
    }

    private fun trim() {
        Files.list(directory).use { files -> files.filter {
            it.fileName.toString().startsWith(".resource-") && it.fileName.toString().endsWith(".tmp") &&
                clock.millis() - Files.getLastModifiedTime(it).toMillis() > Duration.ofDays(1).toMillis()
        }.forEach(::remove) }
        val files = Files.list(directory).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".bin") }.toList()
                .sortedByDescending { Files.getLastModifiedTime(it).toMillis() }
        }
        var bytes = 0L
        files.forEachIndexed { index, file ->
            bytes += Files.size(file)
            if (index >= maximumEntries || bytes > maximumBytes) remove(file)
        }
    }

    private fun access(path: Path) { runCatching { Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.from(clock.instant())) } }
    private fun remove(path: Path) { runCatching { Files.deleteIfExists(path) } }
    private fun date(value: String?): Long? = runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
    private fun digest(value: String) = Digests.sha256Hex(value)
    private fun digest(value: ByteArray) = Digests.sha256Hex(value)
    private data class Entry(val validated: Long, val headers: Map<String, String>, val bytes: ByteArray)

    companion object {
        private const val VERSION = 1
        private fun root(name: String): Path =
            (System.getenv("XDG_CACHE_HOME")?.takeIf(String::isNotBlank)?.let(Path::of)
                ?: Path.of(System.getProperty("user.home"), ".cache")).resolve("tvbox-desktop/resources-v1/$name")
        val configurations by lazy { ResourceDiskCache(root("configurations"), Duration.ofHours(1), Duration.ofDays(7), 32, 32L * 1024 * 1024, 16 * 1024 * 1024) }
        val playlists by lazy { ResourceDiskCache(root("playlists"), Duration.ofMinutes(15), Duration.ofDays(1), 16, 32L * 1024 * 1024, 16 * 1024 * 1024) }
        val modules by lazy { ResourceDiskCache(root("modules"), Duration.ZERO, Duration.ZERO, 128, 32L * 1024 * 1024, 2 * 1024 * 1024) }
        val epg by lazy { ResourceDiskCache(root("epg"), Duration.ofHours(6), Duration.ofDays(1), 2, 128L * 1024 * 1024, 64 * 1024 * 1024) }
    }
}
