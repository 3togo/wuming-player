package com.wuming.tv.desktop.config

import com.wuming.tv.core.base.Digests
import com.wuming.tv.core.runtime.js.JsSourceSpec
import com.wuming.tv.core.runtime.jvmspider.JvmSpiderSiteSpec
import com.wuming.tv.core.live.LivePlaylistReference
import com.wuming.tv.core.maccms.MacCmsSiteSpec
import com.wuming.tv.core.catalog.RemoteSpiderSiteSpec
import com.wuming.tv.core.playback.HlsAdRule
import com.wuming.tv.core.source.SpiderLocation
import com.wuming.tv.core.source.TvBoxSourceRuntime
import com.wuming.tv.core.source.config.TvBoxConfigDecoder
import com.wuming.tv.core.source.config.TvBoxLiveConfig
import com.wuming.tv.desktop.net.DesktopResourceLoader
import com.wuming.tv.desktop.net.ghproxyUrl
import com.wuming.tv.desktop.cache.ResourceDiskCache
import com.wuming.tv.desktop.cache.ResourceOrigin
import com.wuming.tv.core.source.TvBoxConfigPayloadDecoder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.logging.Level
import java.util.logging.Logger

data class DesktopTvBoxSite(
    val key: String,
    val name: String,
    val searchable: Boolean,
    val indexable: Boolean,
    val runtime: TvBoxSourceRuntime,
    val execution: JsSourceSpec? = null,
    val jvmExecution: JvmSpiderSiteSpec? = null,
    val apiExecution: MacCmsSiteSpec? = null,
    val remoteExecution: RemoteSpiderSiteSpec? = null,
    val categories: List<String> = emptyList(),
    val browseEnabled: Boolean = true,
    val inputOnly: Boolean = false,
    val pythonExecution: com.wuming.tv.core.runtime.python.PythonSourceSpec? = null,
    val libvioExecution: com.wuming.tv.core.libvio.LibvioSiteSpec? = null,
)

typealias DesktopTvBoxLive = LivePlaylistReference

data class DesktopTvBoxConfiguration(
    val location: String,
    val resolvedLocation: String,
    val sites: List<DesktopTvBoxSite>,
    val lives: List<DesktopTvBoxLive>,
    val spiderConfigured: Boolean,
    val hlsAdRules: List<HlsAdRule> = emptyList(),
    val reusedCache: Boolean = false,
    val jsonFingerprint: String = "",
    val cacheStatus: String = "network",
) {
    val searchableSites: List<DesktopTvBoxSite> get() = sites.filter { it.searchable }
    /** Home/category visibility is independent of search and discovery-index flags. */
    val browsableSites: List<DesktopTvBoxSite> get() = sites.filter { it.browseEnabled }
    val directJavaScriptSites: List<DesktopTvBoxSite>
        get() = browsableSites.filter { it.runtime == TvBoxSourceRuntime.DIRECT_JAVASCRIPT }
    val jvmSpiderSites: List<DesktopTvBoxSite>
        get() = browsableSites.filter { it.jvmExecution != null }
    /** `type` 0/1 collection APIs: plain HTTP sources, enabled without provider approval. */
    val macCmsSites: List<DesktopTvBoxSite>
        get() = browsableSites.filter { it.apiExecution != null }
    /** `type` 4 remote CatVod-compatible HTTP endpoints. */
    val remoteSpiderSites: List<DesktopTvBoxSite>
        get() = browsableSites.filter { it.remoteExecution != null }
}

class DesktopTvBoxConfigurationLoader(
    private val cache: DesktopConfigurationCache? = DesktopConfigurationCache(),
    private val loadResource: ((String) -> ByteArray)? = null,
) {
    private val logger = Logger.getLogger("tvbox.config")

    fun load(location: String, forceRefresh: Boolean = false): DesktopTvBoxConfiguration {
        val raw = location.trim()
        require(raw.isNotEmpty()) { "Enter a configuration URL or file." }
        val input = ghproxyUrl(if (loadResource == null) com.wuming.tv.desktop.packages.DesktopSourcePackages.transport(raw) else raw)
        logger.info("load start location=${safeLocation(input)} forceRefresh=$forceRefresh")
        // The default transport owns HTTP-aware caching. The injected byte loader retains
        // the legacy decoded-cache contract for embedders and deterministic tests.
        if (loadResource == null) {
            var origin = ResourceOrigin.NETWORK
            var cacheable = false
            if (cache == null) return parse(input,
                DesktopConfigurationPayloadResolver(DesktopResourceLoader()::load).load(input), false, input).copy(location = raw)
            val resolver = DesktopConfigurationPayloadResolver { target ->
                ResourceDiskCache.configurations.load(target, forceRefresh = forceRefresh) { bytes ->
                    runCatching {
                        parse(input, ResolvedPayload(target, TvBoxConfigPayloadDecoder.decode(bytes)), false)
                    }.isSuccess
                }.also { origin = it.origin; cacheable = it.cacheable }.bytes
            }
            val payload = try {
                resolver.load(input)
            } catch (failure: Exception) {
                val transient = failure is java.io.IOException ||
                    (failure is com.wuming.tv.desktop.net.DesktopHttpStatusException && failure.statusCode in 500..599)
                if (!transient || Thread.currentThread().isInterrupted) throw failure
                val fallback = ResourceDiskCache.configurations.fallbackLocation(input) ?: throw failure
                resolver.load(fallback)
            }
            val result = parse(input, payload, origin != ResourceOrigin.NETWORK, input).copy(location = raw)
                .copy(cacheStatus = origin.name.lowercase())
            if (cacheable && origin in setOf(ResourceOrigin.NETWORK, ResourceOrigin.REVALIDATED)) {
                ResourceDiskCache.configurations.rememberFallback(input, payload.location)
            }
            return result
        }
        val payloadResolver = DesktopConfigurationPayloadResolver(loadResource)
        val freshCache = cache
            ?.takeUnless { forceRefresh || !isRemoteLocation(input) }
            ?.readFresh(input, FRESH_CACHE_AGE)
        var reusedCache = freshCache != null
        val previousCache = cache?.takeUnless { forceRefresh }?.read(input)
        val resolvedPayload = freshCache?.also {
            logger.info("fresh cache hit location=${safeLocation(input)} jsonBytes=${it.json.toByteArray().size}")
        } ?: runCatching { payloadResolver.load(input) }.map { downloaded ->
                if (previousCache != null && downloaded == previousCache) {
                    reusedCache = true
                    cache.touch(input)
                    logger.info("remote content unchanged; refreshed cache age location=${safeLocation(input)}")
                }
                downloaded
            }.getOrElse { failure ->
                logger.log(Level.WARNING, "remote load failed location=${safeLocation(input)} error=${failure.message}", failure)
                cache?.read(input)?.also {
                    reusedCache = true
                    logger.info("stale cache fallback location=${safeLocation(input)} jsonBytes=${it.json.toByteArray().size}")
                } ?: run {
                    logger.warning("cache miss location=${safeLocation(input)}")
                    throw failure
                }
            }
        val result = parse(input, resolvedPayload, reusedCache, input).copy(location = raw)
        if (!reusedCache) cache?.write(input, resolvedPayload)
        return result
    }

    private fun parse(input: String, resolvedPayload: ResolvedPayload, reusedCache: Boolean, resolutionBase: String = input): DesktopTvBoxConfiguration {
        val resolved = resolvedPayload.location
        val base = resolutionBase
        val json = resolvedPayload.json
        val root = runCatching { com.wuming.tv.core.source.config.ConfigResourceResolver.rewrite(JsonParser.parseString(json).asJsonObject) { com.wuming.tv.core.source.config.ConfigResourceResolver.resolve(base, it) } }
            .getOrElse { throw IllegalArgumentException("Decoded configuration JSON is invalid.") }
        val decoded = TvBoxConfigDecoder.decode(root)
        val rootSpider = resolveResource(base, SpiderLocation.locationOf(decoded.spider.orEmpty()))
        val sites = decoded.sites
            .mapNotNull { mapSite(it, rootSpider, base, root.get("wumingPackageId")?.asString.orEmpty()) }
            .distinctBy { it.key }
        // The shared decoder has already refused unusable lives (no url, no name, not an object);
        // this step only turns the survivors into locations relative to the configuration.
        val lives = decoded.lives.map { live(it, base) }
        val hlsAdRules = array(root, "rules").mapNotNull(::hlsAdRule)
        require(sites.isNotEmpty() || lives.isNotEmpty()) {
            "Configuration contains neither sites nor live playlists."
        }
        val jsonFingerprint = fullDigest(json)
        val result = DesktopTvBoxConfiguration(
            location = input,
            resolvedLocation = resolved,
            sites = sites,
            lives = lives,
            spiderConfigured = rootSpider.isNotBlank(),
            hlsAdRules = hlsAdRules,
            reusedCache = reusedCache,
            jsonFingerprint = jsonFingerprint,
        )
        logger.info(
            "load success cache=$reusedCache resolved=${safeLocation(resolved)} sites=${sites.size} " +
                "searchable=${result.searchableSites.size} lives=${lives.size} hlsAdRules=${hlsAdRules.size} " +
                "fingerprint=$jsonFingerprint",
        )
        decoded.admissions.forEach {
            if (it.noteworthy) logger.warning(it.summary()) else logger.info(it.summary())
        }
        return result
    }

    /**
     * Maps the platform-agnostic [TvBoxConfigDecoder] site model into the
     * desktop [DesktopTvBoxSite]. The shared decoder owns all field extraction
     * (type/api/ext/jar classification, fallback detection); this method only
     * applies desktop-specific validation and builds the JVM execution specs.
     */
    private fun mapSite(
        config: com.wuming.tv.core.source.config.TvBoxSiteConfig,
        rootSpider: String,
        baseLocation: String,
        packageId: String = "",
    ): DesktopTvBoxSite? {
        val rawKey = config.key.trim()
        val key = if (packageId.matches(Regex("[a-f0-9]{32}"))) com.wuming.tv.core.source.packages.PackageSourceIdentity.scoped(packageId, rawKey) else rawKey
        if (rawKey.isEmpty() || rawKey.any(Character::isISOControl)) return null
        val name = config.name.trim().ifBlank { key }
        val ownLibvio = config.type == 3 && com.wuming.tv.core.libvio.LibvioSiteSpec.matches(config.api)
        val artifact = resolveResource(baseLocation, SpiderLocation.locationOf(config.jar.trim().ifBlank { rootSpider }))
        val api = resolveResource(baseLocation, config.api)
        val extension = if (isRelativeLocation(config.ext)) resolveResource(baseLocation, config.ext) else config.ext
        return DesktopTvBoxSite(
            key,
            name,
            config.searchable != 0 && !config.hide,
            config.indexs == "1" && !config.hide,
            config.runtime,
            if (config.runtime == TvBoxSourceRuntime.DIRECT_JAVASCRIPT &&
                (api.startsWith("http://") || api.startsWith("https://"))
            ) {
                runCatching {
                    JsSourceSpec(key, name, api, extension, config.categories, rawKey, if (key != rawKey) "package://$packageId/$rawKey" else api)
                }.getOrNull()
            } else null,
            if (!ownLibvio && config.runtime == TvBoxSourceRuntime.SPIDER_RUNTIME_REQUIRED && config.api.startsWith("csp_") && artifact.isNotBlank()) {
                runCatching {
                    JvmSpiderSiteSpec(key, name, config.api, extension, artifact, config.categories, rawKey)
                }.getOrNull()
            } else null,
            if (config.runtime == TvBoxSourceRuntime.MAC_CMS_API &&
                (api.startsWith("http://") || api.startsWith("https://"))
            ) {
                runCatching {
                    MacCmsSiteSpec(key, name, api, config.type, extension, config.header, config.categories)
                }.getOrNull()
            } else null,
            if (config.runtime == TvBoxSourceRuntime.REMOTE_SPIDER_HTTP &&
                (api.startsWith("http://") || api.startsWith("https://"))
            ) {
                runCatching {
                    RemoteSpiderSiteSpec(key, name, api, extension, config.header, config.categories)
                }.getOrNull()
            } else null,
            config.categories,
            browseEnabled = config.browsable,
            inputOnly = config.inputOnly,
            libvioExecution = if (ownLibvio) com.wuming.tv.core.libvio.LibvioSiteSpec.from(key, name, extension).copy(categories = config.categories) else null,
            pythonExecution = if (config.type == 3 && api.substringBefore('?').endsWith(".py", true)) {
                com.wuming.tv.core.runtime.python.PythonSourceSpec(key, name, api, extension, rawKey, config.categories,
                    if (key != rawKey) "package://$packageId/$rawKey" else "$key/$api")
            } else null,
        )
    }

    private fun live(config: TvBoxLiveConfig, baseLocation: String): DesktopTvBoxLive = LivePlaylistReference(
        name = config.name.trim(),
        location = resolveResource(baseLocation, config.url.trim()),
    )

    private fun hlsAdRule(element: JsonElement): HlsAdRule? {
        if (!element.isJsonObject) return null
        val value = element.asJsonObject
        val hosts = strings(value, "hosts").filter(::safeRuleValue).distinct().take(MAX_RULE_VALUES)
        val patterns = strings(value, "regex").filter(::safeRuleValue).distinct().take(MAX_RULE_VALUES)
        if (hosts.isEmpty() || patterns.isEmpty()) return null
        return HlsAdRule(
            name = string(value, "name").trim().take(MAX_RULE_NAME_LENGTH),
            hosts = hosts,
            patterns = patterns,
        )
    }

    private fun array(root: JsonObject, name: String): List<JsonElement> =
        root.get(name)?.takeIf(JsonElement::isJsonArray)?.asJsonArray?.toList().orEmpty()

    private fun string(root: JsonObject, name: String): String = runCatching {
        root.get(name)?.takeIf(JsonElement::isJsonPrimitive)?.asString.orEmpty()
    }.getOrDefault("")

    private fun strings(root: JsonObject, name: String): List<String> = runCatching {
        root.get(name)?.takeIf(JsonElement::isJsonArray)?.asJsonArray?.toList().orEmpty()
            .mapNotNull { it.takeIf(JsonElement::isJsonPrimitive)?.asString?.trim() }
    }.getOrDefault(emptyList())

    /**
     * Resolves a configuration-relative resource (spider jar, api, ext, live playlist) against the
     * configuration host and routes the result through ghproxy when it targets GitHub, so desktop tools
     * work in networks where raw.githubusercontent.com is blocked. Relative resolution runs first
     * (against the raw, non-proxied base) so the embedded target stays a clean absolute URL.
     */
    private fun resolveResource(base: String, value: String): String = ghproxyUrl(resolveLocation(base, value))

    private fun resolveLocation(base: String, value: String): String {
        return com.wuming.tv.core.source.config.ConfigResourceResolver.resolve(base, value)
    }

    /**
     * Site `ext` values are either opaque data (base64 blobs, inline JSON) or a relative rule
     * location such as `./lib/bplm.js`. Only the latter may be resolved against the URL.
     */
    private fun isRelativeLocation(value: String): Boolean = when {
        value.startsWith("./") || value.startsWith("../") -> true
        value.startsWith("/") || value.contains("://") -> false
        else -> RELATIVE_SCRIPT.matches(value)
    }

    private fun safeRuleValue(value: String): Boolean =
        value.isNotBlank() && value.length <= MAX_RULE_VALUE_LENGTH && value.none(Character::isISOControl)

    private fun safeLocation(value: String): String = runCatching {
        val uri = URI(value)
        if (uri.scheme == null) "local-file" else "${uri.scheme}://${uri.host ?: "idn-host"}${uri.path.orEmpty()}"
    }.getOrDefault("invalid-location")

    private fun isRemoteLocation(value: String): Boolean =
        runCatching { URI(value).scheme?.lowercase() in setOf("http", "https") }.getOrDefault(false)

    private fun fullDigest(value: String): String = Digests.sha256Hex(value)

    private companion object {
        val RELATIVE_SCRIPT = Regex("[^\\s]+/[^\\s]*\\.(?:m?js|json|txt)")
        val FRESH_CACHE_AGE: Duration = Duration.ofHours(1)
        const val MAX_RULE_VALUES = 64
        const val MAX_RULE_NAME_LENGTH = 128
        const val MAX_RULE_VALUE_LENGTH = 4_096
    }
}

internal data class ResolvedPayload(val location: String, val json: String)
