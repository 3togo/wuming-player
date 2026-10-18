package com.wuming.tv.desktop.cache

import com.wuming.tv.desktop.net.DesktopHttpStatusException
import com.wuming.tv.desktop.net.DesktopResourceResponse
import java.io.IOException
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

internal class MutableCacheClock(var millisNow: Long = 1_800_000_000_000) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = Instant.ofEpochMilli(millisNow)
}

class ResourceDiskCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val clock = MutableCacheClock()
    private val url = "https://example.test/feed?variant=1"
    private fun response(headers: Map<String, String> = emptyMap(), value: String = "valid", status: Int = 200) =
        DesktopResourceResponse(value.toByteArray(), status, url, headers)
    private fun cache(name: String = "cache", fetch: (String, Map<String, String>) -> DesktopResourceResponse) =
        ResourceDiskCache(temporary.root.toPath().resolve(name), Duration.ofMinutes(15), Duration.ofDays(1),
            2, 4096, 1024, clock, fetch)
    private fun ResourceDiskCache.read(force: Boolean = false, headers: Map<String, String> = emptyMap()) =
        load(url, headers, force) { require(it.toString(Charsets.UTF_8) == "valid"); true }

    @Test fun freshHitAndConditionalRevalidationSurviveRestart() {
        var requests = 0
        val fetch = { _: String, headers: Map<String, String> ->
            requests++
            if (requests == 1) response(mapOf("etag" to "\"v1\"", "cache-control" to "max-age=60"))
            else {
                assertEquals("\"v1\"", headers["If-None-Match"])
                response(mapOf("cache-control" to "max-age=60"), "", 304)
            }
        }
        assertEquals(ResourceOrigin.NETWORK, cache(fetch = fetch).read().origin)
        assertEquals(ResourceOrigin.FRESH, cache(fetch = fetch).read().origin)
        clock.millisNow += 60_000
        assertEquals(ResourceOrigin.REVALIDATED, cache(fetch = fetch).read().origin)
        assertEquals(ResourceOrigin.FRESH, cache(fetch = fetch).read().origin)
        assertEquals(2, requests)
    }

    @Test fun staleFallbackDoesNotRenewValidationAndExpires() {
        var offline = false
        var requests = 0
        val cache = cache { _, _ -> requests++; if (offline) throw IOException("offline") else response() }
        cache.read()
        offline = true
        clock.millisNow += 900_000
        repeat(2) { assertEquals(ResourceOrigin.STALE, cache.read().origin) }
        clock.millisNow += Duration.ofDays(1).toMillis()
        assertThrows(IOException::class.java) { cache.read() }
        assertEquals(4, requests)
    }

    @Test fun serverDirectivesAndCredentialsPreventReuse() {
        for ((index, headers) in listOf(mapOf("cache-control" to "no-store"), mapOf("vary" to "*"),
            mapOf("set-cookie" to "session=secret")).withIndex()) {
            var calls = 0
            val cache = cache("private-$index") { _, _ -> calls++; response(headers) }
            repeat(2) { assertEquals(ResourceOrigin.NETWORK, cache.read().origin) }
            assertEquals(2, calls)
        }
        var calls = 0
        val cache = cache("auth") { _, _ -> calls++; response() }
        repeat(2) { cache.read(headers = mapOf("Authorization" to "Bearer secret")) }
        assertEquals(2, calls)
    }

    @Test fun noCacheAndMustRevalidateNeverFallBackOffline() {
        for (directive in listOf("no-cache", "max-age=0, must-revalidate")) {
            var offline = false
            val cache = cache(directive.replace(',', '_')) { _, _ ->
                if (offline) throw IOException("offline") else response(mapOf("cache-control" to directive))
            }
            cache.read()
            offline = true
            assertThrows(IOException::class.java) { cache.read() }
        }
    }

    @Test fun varyVariantsAndForcedRefreshUseSeparateRequests() {
        var calls = 0
        val cache = cache { _, _ -> calls++; response(mapOf("vary" to "User-Agent")) }
        cache.read(headers = mapOf("User-Agent" to "one"))
        cache.read(headers = mapOf("user-agent" to "two"))
        assertEquals(ResourceOrigin.FRESH, cache.read(headers = mapOf("USER-AGENT" to "one")).origin)
        cache.read(force = true, headers = mapOf("User-Agent" to "one"))
        assertEquals(3, calls)
    }

    @Test fun invalidBodyDoesNotReplaceGoodSnapshotAndCorruptionRefetches() {
        var body = "valid"
        val cache = cache { _, _ -> response(value = body) }
        cache.read()
        body = "invalid"
        assertThrows(IllegalArgumentException::class.java) { cache.read(force = true) }
        assertEquals(ResourceOrigin.FRESH, cache.read().origin)
        val file = Files.list(temporary.root.toPath().resolve("cache")).use { it.findFirst().get() }
        Files.writeString(file, "corrupt")
        body = "valid"
        assertEquals(ResourceOrigin.NETWORK, cache.read().origin)
    }

    @Test fun unauthorizedResponseDoesNotServeStaleAndNoStoreRemovesOldEntry() {
        var mode = 0
        val cache = cache { _, _ -> when (mode) {
            1 -> throw DesktopHttpStatusException(401, "")
            2 -> response(mapOf("cache-control" to "no-store"))
            else -> response()
        } }
        cache.read()
        clock.millisNow += 900_000
        mode = 1
        assertThrows(DesktopHttpStatusException::class.java) { cache.read() }
        mode = 2
        cache.read()
        mode = 0
        assertEquals(ResourceOrigin.NETWORK, cache.read().origin)
    }

    @Test fun capsEntriesAndDoesNotCacheLocalFiles() {
        var requests = 0
        val cache = cache { _, _ -> requests++; response() }
        repeat(3) { n -> clock.millisNow++; cache.load("https://example.test/$n") { true } }
        assertEquals(2L, Files.list(temporary.root.toPath().resolve("cache")).use { it.count() })
        repeat(2) { cache.load("/tmp/local-config") { true } }
        assertEquals(5, requests)
        cache.clear()
        assertEquals(0L, Files.list(temporary.root.toPath().resolve("cache")).use { it.count() })
    }

    @Test fun fallbackIndexExpiresAndIsClearedWithResources() {
        val cache = cache { _, _ -> response() }
        cache.rememberFallback(url, "https://example.test/fallback")
        assertEquals("https://example.test/fallback", cache.fallbackLocation(url))
        clock.millisNow += Duration.ofDays(1).toMillis()
        assertNull(cache.fallbackLocation(url))
        cache.rememberFallback(url, "https://example.test/fallback")
        cache.clear()
        assertNull(cache.fallbackLocation(url))
    }

    @Test fun serverAgeShortensFreshnessAndStaleCeiling() {
        var offline = false
        val cache = cache { _, _ ->
            if (offline) throw IOException("offline")
            response(mapOf("cache-control" to "max-age=60", "age" to "59"))
        }
        cache.read()
        assertEquals(ResourceOrigin.FRESH, cache.read().origin)
        clock.millisNow += 1000
        offline = true
        assertEquals(ResourceOrigin.STALE, cache.read().origin)
        clock.millisNow += Duration.ofDays(1).toMillis()
        assertThrows(IOException::class.java) { cache.read() }
    }

    @Test fun serverAgeCannotExtendStaleFallbackPastItsCeiling() {
        var offline = false
        val almostOneDay = Duration.ofDays(1).minusSeconds(1).seconds
        val cache = cache { _, _ ->
            if (offline) throw IOException("offline")
            response(mapOf("cache-control" to "max-age=0", "age" to almostOneDay.toString()))
        }
        cache.read()
        clock.millisNow += 2_000
        offline = true

        assertThrows(IOException::class.java) { cache.read() }
    }

    @Test fun missingBody304RetriesWithoutValidators() {
        var requests = 0
        val cache = cache { _, headers ->
            assertFalse(headers.containsKey("If-None-Match"))
            if (++requests == 1) response(status = 304, value = "") else response()
        }
        assertEquals(ResourceOrigin.NETWORK, cache.read().origin)
        assertEquals(2, requests)
    }
}
