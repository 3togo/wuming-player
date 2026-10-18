package com.wuming.tv.desktop.config

import com.wuming.tv.core.source.TvBoxSourceRuntime
import com.wuming.tv.desktop.jvmspider.GuardedDesktopSupport
import com.wuming.tv.desktop.jvmspider.GuardedDesktopSupportPolicy
import com.wuming.tv.desktop.jvmspider.GuardedSourceActivator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

class DesktopTvBoxConfigurationLoaderTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun loadsCommentPrefixedHostedConfiguration() {
        val cache = DesktopConfigurationCache(temporary.newFolder("comment-prefix").toPath())
        val payload = "// 小盒子接口说明\n/* 免费分享 */\n" +
            """{"sites":[{"key":"demo","type":3,"api":"csp_Demo"}]}"""
        val result = DesktopTvBoxConfigurationLoader(cache) { payload.toByteArray() }
            .load("https://example.test/config")
        assertEquals("demo", result.sites.single().key)
    }

    @Test
    fun failedRefreshDoesNotMakeStaleConfigurationFresh() {
        val root = temporary.newFolder("stale-fallback").toPath()
        val writtenAt = Instant.parse("2026-08-11T00:00:00Z")
        val location = "https://example.test/config"
        val json = """{"sites":[{"key":"cached","type":3,"api":"csp_Demo"}]}"""
        DesktopTvBoxConfigurationLoader(
            DesktopConfigurationCache(root, Clock.fixed(writtenAt, ZoneOffset.UTC)),
        ) { json.toByteArray() }.load(location)
        val cache = DesktopConfigurationCache(root, Clock.fixed(writtenAt.plusSeconds(7200), ZoneOffset.UTC))
        var attempts = 0
        val loader = DesktopTvBoxConfigurationLoader(cache) {
            attempts++
            error("offline")
        }

        repeat(2) {
            val result = loader.load(location)
            assertTrue(result.reusedCache)
            assertEquals("cached", result.sites.single().key)
            assertEquals(null, cache.readFresh(location, java.time.Duration.ofHours(1)))
        }
        assertEquals(2, attempts)
    }

    @Test
    fun usesValidatedCacheWithoutNetworkWhenItIsLessThanOneHourOld() {
        val root = temporary.newFolder("fresh-cache").toPath()
        val clock = Clock.fixed(Instant.parse("2026-08-13T00:00:00Z"), ZoneOffset.UTC)
        val cache = DesktopConfigurationCache(root, clock)
        val location = "https://example.test/config"
        val json = """{"sites":[{"key":"cached","type":3,"api":"csp_Demo"}]}"""
        DesktopTvBoxConfigurationLoader(cache) { json.toByteArray() }.load(location)
        var networkRequests = 0

        val result = DesktopTvBoxConfigurationLoader(cache) {
            networkRequests++
            error("network should not be used")
        }.load(location)

        assertTrue(result.reusedCache)
        assertEquals("cached", result.sites.single().key)
        assertEquals(0, networkRequests)
    }

    @Test
    fun refreshesCacheAfterOneHour() {
        val root = temporary.newFolder("expired-cache").toPath()
        val writtenAt = Instant.parse("2026-08-11T00:00:00Z")
        val location = "https://example.test/config"
        val oldJson = """{"sites":[{"key":"old","type":3,"api":"csp_Demo"}]}"""
        DesktopTvBoxConfigurationLoader(
            DesktopConfigurationCache(root, Clock.fixed(writtenAt, ZoneOffset.UTC)),
        ) { oldJson.toByteArray() }.load(location)
        var networkRequests = 0
        val newJson = """{"sites":[{"key":"new","type":3,"api":"csp_Demo"}]}"""
        val expiredCache = DesktopConfigurationCache(
            root,
            Clock.fixed(writtenAt.plusSeconds(60 * 60), ZoneOffset.UTC),
        )

        val result = DesktopTvBoxConfigurationLoader(expiredCache) {
            networkRequests++
            newJson.toByteArray()
        }.load(location)

        assertTrue(!result.reusedCache)
        assertEquals("new", result.sites.single().key)
        assertEquals(1, networkRequests)
    }

    @Test
    fun reusesExpiredCacheWhenDecodedRemoteContentIsUnchanged() {
        val root = temporary.newFolder("unchanged-cache").toPath()
        val writtenAt = Instant.parse("2026-08-11T00:00:00Z")
        val location = "https://example.test/config"
        val json = """{"sites":[{"key":"same","type":3,"api":"csp_Demo"}]}"""
        DesktopTvBoxConfigurationLoader(
            DesktopConfigurationCache(root, Clock.fixed(writtenAt, ZoneOffset.UTC)),
        ) { json.toByteArray() }.load(location)
        var networkRequests = 0
        val refreshedClock = Clock.fixed(writtenAt.plusSeconds(60 * 60), ZoneOffset.UTC)
        val cache = DesktopConfigurationCache(root, refreshedClock)

        val result = DesktopTvBoxConfigurationLoader(cache) {
            networkRequests++
            json.toByteArray()
        }.load(location)

        assertTrue(result.reusedCache)
        assertEquals("same", result.sites.single().key)
        assertEquals(1, networkRequests)
        assertTrue(cache.readFresh(location, java.time.Duration.ofHours(1)) != null)
    }

    @Test
    fun forceRefreshBypassesFreshCache() {
        val root = temporary.newFolder("forced-cache").toPath()
        val clock = Clock.fixed(Instant.parse("2026-08-13T00:00:00Z"), ZoneOffset.UTC)
        val cache = DesktopConfigurationCache(root, clock)
        val location = "https://example.test/config"
        val oldJson = """{"sites":[{"key":"old","type":3,"api":"csp_Demo"}]}"""
        DesktopTvBoxConfigurationLoader(cache) { oldJson.toByteArray() }.load(location)
        var networkRequests = 0
        val newJson = """{"sites":[{"key":"new","type":3,"api":"csp_Demo"}]}"""

        val result = DesktopTvBoxConfigurationLoader(cache) {
            networkRequests++
            newJson.toByteArray()
        }.load(location, forceRefresh = true)

        assertTrue(!result.reusedCache)
        assertEquals("new", result.sites.single().key)
        assertEquals(1, networkRequests)
    }

    @Test
    fun reusesOnlyAPreviouslyValidatedConfigurationWhenRemoteLoadingFails() {
        val location = "https://example.test/stable-config"
        val cache = DesktopConfigurationCache(temporary.newFolder("cache").toPath())
        val json = """{"sites":[{"key":"cached","type":3,"api":"csp_Demo"}]}"""
        val online = DesktopTvBoxConfigurationLoader(cache) { json.toByteArray() }.load(location)
        val offline = DesktopTvBoxConfigurationLoader(cache) { error("network unavailable") }.load(location)

        assertTrue(!online.reusedCache)
        assertTrue(offline.reusedCache)
        assertEquals("cached", offline.sites.single().key)
    }

    @Test
    fun retriesOneTransientUndecodableResponse() {
        val responses = ArrayDeque(
            listOf(
                "temporary upstream page".toByteArray(),
                """{"sites":[{"key":"recovered","type":3,"api":"csp_Demo"}]}""".toByteArray(),
            ),
        )
        val loader = DesktopTvBoxConfigurationLoader(cache = null) { responses.removeFirst() }

        val result = loader.load("https://example.test/config")

        assertEquals("recovered", result.sites.single().key)
        assertTrue(responses.isEmpty())
    }

    @Test
    fun followsMirrorsOnLandingPageThatOmitsItsOwnUrl() {
        val landing = "http://landing.example.test/tv"
        val broken = "http://broken.example.test/tv"
        val working = "https://working.example.test/config.bmp"
        val html = """<html><div data-clipboard-text="$broken"></div><div data-clipboard-text="$working"></div></html>"""
        val json = """{"sites":[{"key":"mirror","type":3,"api":"csp_Demo"}]}"""
        val envelope = "Ab12Cd34**" + Base64.getEncoder().encodeToString(json.toByteArray(StandardCharsets.UTF_8))
        val visited = mutableListOf<String>()
        val loader = DesktopTvBoxConfigurationLoader(cache = null) { url ->
            visited += url
            when (url) {
                landing -> html.toByteArray()
                broken -> "unavailable".toByteArray()
                working -> envelope.toByteArray()
                else -> error("Unexpected URL")
            }
        }

        val result = loader.load(landing)

        assertEquals("mirror", result.sites.single().key)
        assertEquals(working, result.resolvedLocation)
        assertEquals(listOf(landing, broken, working), visited)
    }

    @Test
    fun keepsExecutionPayloadRedactedFromConfigurationDiagnostics() {
        val json = """
            {
              "spider":"https://example.test/runtime.jar",
              "sites":[
                {"key":"jar","name":"Jar site","type":3,"api":"csp_Demo","searchable":1,"categories":["Anime","Movies","Anime"],"ext":{"token":"secret"}},
                {"key":"js","name":"JS site","type":3,"api":"https://example.test/site.js","searchable":"1","ext":{"token":"js-secret"}},
                {"key":"hidden","type":3,"api":"csp_Hidden","hide":1}
              ],
              "rules":[
                {"name":"Demo ads","hosts":["cdn.example"],"regex":["5.480000","5.480000"]},
                {"name":"Incomplete","hosts":[],"regex":["9.0"]}
              ],
              "lives":[{"name":"Live","url":"https://example.test/live.m3u8"}]
            }
        """.trimIndent()
        val loader = DesktopTvBoxConfigurationLoader(cache = null) { json.toByteArray() }

        val result = loader.load("https://example.test/config")

        assertEquals(3, result.sites.size)
        assertEquals(2, result.searchableSites.size)
        assertEquals(TvBoxSourceRuntime.SPIDER_RUNTIME_REQUIRED, result.sites[0].runtime)
        assertEquals(TvBoxSourceRuntime.DIRECT_JAVASCRIPT, result.sites[1].runtime)
        assertEquals(1, result.directJavaScriptSites.size)
        assertEquals(1, result.jvmSpiderSites.size)
        assertEquals("com.github.catvod.spider.Demo", result.jvmSpiderSites.single().jvmExecution!!.className)
        assertEquals("https://example.test/runtime.jar", result.jvmSpiderSites.single().jvmExecution!!.artifactLocation)
        assertEquals(listOf("Anime", "Movies"), result.jvmSpiderSites.single().categories)
        assertEquals(listOf("Anime", "Movies"), result.jvmSpiderSites.single().jvmExecution!!.categories)
        assertTrue(result.directJavaScriptSites.single().execution != null)
        assertTrue(result.directJavaScriptSites.single().execution!!.extension.contains("js-secret"))
        assertEquals("Live", result.lives.single().name)
        assertEquals("https://example.test/live.m3u8", result.lives.single().location)
        assertEquals(1, result.hlsAdRules.size)
        assertEquals(listOf("cdn.example"), result.hlsAdRules.single().hosts)
        assertEquals(listOf("5.480000"), result.hlsAdRules.single().patterns)
        assertTrue(result.spiderConfigured)
        assertTrue(result.sites.none { it.toString().contains("secret") })
    }

    @Test
    fun mapsTypeFourHttpSitesToRemoteExecutors() {
        val json = """{"sites":[{"key":"remote","name":"Remote","type":4,"api":"./vod","ext":"token","header":{"User-Agent":"TV"}}]}"""

        val result = DesktopTvBoxConfigurationLoader(cache = null) { json.toByteArray() }
            .load("https://example.test/config/index.json")

        val remote = result.remoteSpiderSites.single().remoteExecution!!
        assertEquals(TvBoxSourceRuntime.REMOTE_SPIDER_HTTP, result.sites.single().runtime)
        assertEquals("https://example.test/config/vod", remote.api)
        assertEquals("token", remote.ext)
        assertEquals("TV", remote.headers["User-Agent"])
    }

    @Test
    fun nonSearchableSitesRemainBrowsableButHiddenAndPushSitesDoNot() {
        val json = """
            {
              "spider":"https://example.test/runtime.jar",
              "sites":[
                {"key":"home","name":"Discovery","type":3,"api":"csp_DouDouGuard","indexs":1,"searchable":0},
                {"key":"browse","name":"Browse only","type":3,"api":"csp_BiliGuard","searchable":0},
                {"key":"hidden","name":"Hidden","type":3,"api":"csp_OtherGuard","searchable":0,"hide":1},
                {"key":"push_agent","name":"Push","type":3,"api":"csp_PushGuard","searchable":0}
              ]
            }
        """.trimIndent()

        val result = DesktopTvBoxConfigurationLoader(cache = null) { json.toByteArray() }
            .load("https://example.test/config")

        assertEquals(emptyList<DesktopTvBoxSite>(), result.searchableSites)
        assertEquals(listOf("home", "browse"), result.browsableSites.map(DesktopTvBoxSite::key))
        assertEquals(listOf("home", "browse"), result.jvmSpiderSites.map(DesktopTvBoxSite::key))
        assertTrue(result.sites.first().indexable)
    }

    @Test
    fun resolvesRelativeSpiderAndScriptLocationsAgainstTheConfigurationUrl() {
        val json = """
            {
              "spider":"./CFlLnSbJ",
              "sites":[
                {"key":"spider","name":"Spider","type":3,"api":"csp_Demo","searchable":1},
                {"key":"script","name":"Script","type":3,"api":"./lib/apiv2542直转点.js","searchable":1},
                {"key":"absolute","name":"Absolute","type":3,"api":"https://cdn.example/other.js","searchable":1}
              ]
            }
        """.trimIndent()
        val location = "https://raw.example/user/repo/refs/heads/main/website/tvbox/18cn.json"

        val result = DesktopTvBoxConfigurationLoader(cache = null) { json.toByteArray() }.load(location)

        assertEquals(
            "https://raw.example/user/repo/refs/heads/main/website/tvbox/CFlLnSbJ",
            result.jvmSpiderSites.single().jvmExecution!!.artifactLocation,
        )
        val scripts = result.directJavaScriptSites.associateBy(DesktopTvBoxSite::key)
        assertEquals(2, scripts.size)
        assertEquals(
            "https://raw.example/user/repo/refs/heads/main/website/tvbox/lib/apiv2542%E7%9B%B4%E8%BD%AC%E7%82%B9.js",
            scripts.getValue("script").execution!!.moduleUrl,
        )
        assertEquals("https://cdn.example/other.js", scripts.getValue("absolute").execution!!.moduleUrl)
    }

    @Test
    fun stripsIntegritySuffixFromRelativeSpiderLocation() {
        val json = """
            {"spider":"./lib/fm.jar;md5;542ed744b07121213f70323b9bcbecca",
             "sites":[{"key":"one","name":"One","type":3,"api":"csp_DemoGuard","searchable":1}]}
        """.trimIndent()
        val site = DesktopTvBoxConfigurationLoader(cache = null) { json.toByteArray() }
            .load("https://host.test/dir/config.json").jvmSpiderSites.single()

        assertEquals("https://host.test/dir/lib/fm.jar", site.jvmExecution!!.artifactLocation)
    }

    @Test
    fun resolvesRelativeDrpyRuleExtensionButLeavesOpaqueExtensionsAlone() {
        val json = """
            {
              "sites":[
                {"key":"drpy","name":"DRPY","type":3,"api":"./lib/drpy2.min.js","searchable":1,"ext":"./lib/bplm.js"},
                {"key":"opaque","name":"Opaque","type":3,"api":"./lib/drpy2.min.js","searchable":1,"ext":"5++kwLhNYm9UrO9wh7Dl7eKamTee4s/5"}
              ]
            }
        """.trimIndent()
        val location = "https://raw.example/user/repo/refs/heads/main/website/tvbox/18cn.json"

        val sites = DesktopTvBoxConfigurationLoader(cache = null) { json.toByteArray() }
            .load(location).directJavaScriptSites.associateBy(DesktopTvBoxSite::key)

        assertEquals(
            "https://raw.example/user/repo/refs/heads/main/website/tvbox/lib/bplm.js",
            sites.getValue("drpy").execution!!.extension,
        )
        assertEquals("5++kwLhNYm9UrO9wh7Dl7eKamTee4s/5", sites.getValue("opaque").execution!!.extension)
    }

    @Test
    fun treatsEveryCspProviderAsAGuardedCandidate() {
        val json = """
            {"spider":"https://example.test/runtime.jar",
             "sites":[{"key":"plain","name":"Plain","type":3,"api":"csp_xBPQ","searchable":1}]}
        """.trimIndent()
        val site = DesktopTvBoxConfigurationLoader(cache = null) { json.toByteArray() }
            .load("https://example.test/config").jvmSpiderSites.single()

        assertTrue(GuardedSourceActivator().isCandidate(site.jvmExecution!!))
        assertEquals(GuardedDesktopSupport.UNVERIFIED, GuardedDesktopSupportPolicy.classify(site.jvmExecution!!))
    }

    @Test
    fun followsOnlyTheFallbackAdvertisedImmediatelyAfterTheRequestedConfiguration() {
        val json = """{"sites":[{"key":"demo","type":3,"api":"csp_Demo"}]}"""
        val html = """
            <html><div data-clipboard-text="http://www.饭太硬.cc/tv"></div>
            <div data-clipboard-text="http://fallback.example/tv"></div></html>
        """.trimIndent()
        val encoded = "Ab12Cd34**" + Base64.getEncoder().encodeToString(json.toByteArray())
        val requested = mutableListOf<String>()
        val loader = DesktopTvBoxConfigurationLoader(cache = null) { location ->
            requested += location
            when (location) {
                "http://www.饭太硬.cc/tv" -> html.toByteArray(StandardCharsets.UTF_8)
                "http://fallback.example/tv" -> encoded.toByteArray(StandardCharsets.ISO_8859_1)
                else -> error("Unexpected URL")
            }
        }

        val result = loader.load("http://www.饭太硬.cc/tv")

        assertEquals(listOf("http://www.饭太硬.cc/tv", "http://fallback.example/tv"), requested)
        assertEquals("http://fallback.example/tv", result.resolvedLocation)
        assertEquals("demo", result.sites.single().key)
    }
}
