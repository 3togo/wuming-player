package com.wuming.tv.desktop.tools

import com.wuming.tv.core.base.Digests
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wuming.tv.desktop.config.DesktopDepot
import com.wuming.tv.desktop.config.DesktopDepotSource
import com.wuming.tv.desktop.net.DesktopResourceLoader
import com.wuming.tv.desktop.net.ghproxyUrl
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Batch-downloads all plugin configurations and JAR artifacts listed in depot.json.
 *
 * For each VOD source URL in depot.json:
 *   1. Downloads the configuration JSON (TVBox/CatVod format).
 *   2. Parses the configuration to extract site definitions.
 *   3. Extracts and downloads referenced JAR spider artifacts.
 *   4. Stores everything in a structured output directory.
 *
 * Output layout:
 *   outputDir/
 *     configs/          — raw downloaded configuration JSONs
 *     jars/             — downloaded JAR spider artifacts
 *     manifest.json     — download manifest with hashes and metadata
 */
object PluginBatchDownloader {

    private val logger = Logger.getLogger("tvbox.tools.plugin-downloader")
    private val gson = Gson()

    data class DownloadedConfig(
        val sourceName: String,
        val sourceUrl: String,
        val status: String,
        val error: String = "",
        val siteCount: Int = 0,
        val jarCount: Int = 0,
        val cloudProviderSites: Int = 0,
        val jsonBytes: Int = 0,
        val sha256: String = "",
    )

    data class DownloadedJar(
        val sourceConfig: String,
        val jarUrl: String,
        val status: String,
        val error: String = "",
        val bytes: Int = 0,
        val sha256: String = "",
        val referencedBySites: List<String> = emptyList(),
    )

    data class DownloadManifest(
        val totalConfigs: Int,
        val successfulConfigs: Int,
        val failedConfigs: Int,
        val totalJars: Int,
        val successfulJars: Int,
        val failedJars: Int,
        val configs: List<DownloadedConfig>,
        val jars: List<DownloadedJar>,
    )

    fun run(outputDir: Path, maxParallel: Int = 4): DownloadManifest {
        val depot = DesktopDepot.load()
        val configsDir = outputDir.resolve("configs")
        val jarsDir = outputDir.resolve("jars")
        Files.createDirectories(configsDir)
        Files.createDirectories(jarsDir)

        logger.info("depot loaded: ${depot.vod.size} VOD sources")
        println("Plugin Batch Downloader")
        println("  VOD sources: ${depot.vod.size}")
        println("  Output: $outputDir")
        println()

        val loader = DesktopResourceLoader()
        val configResults = ConcurrentHashMap.newKeySet<DownloadedConfig>()
        val jarResults = ConcurrentHashMap.newKeySet<DownloadedJar>()
        val downloadedJarUrls = ConcurrentHashMap.newKeySet<String>()

        val executor = Executors.newFixedThreadPool(maxParallel.coerceAtLeast(1))
        val completed = AtomicInteger(0)

        depot.vod.forEach { source ->
            executor.submit {
                try {
                    downloadConfig(source, loader, configsDir, jarsDir, configResults, jarResults, downloadedJarUrls)
                } catch (e: Exception) {
                    logger.log(Level.WARNING, "config download failed: ${source.name}", e)
                    configResults.add(
                        DownloadedConfig(source.name, source.location, "FAILED", e.message ?: e.javaClass.simpleName),
                    )
                } finally {
                    val done = completed.incrementAndGet()
                    println("  [$done/${depot.vod.size}] ${source.name}")
                }
            }
        }

        executor.shutdown()
        executor.awaitTermination(10, TimeUnit.MINUTES)

        val manifest = DownloadManifest(
            totalConfigs = depot.vod.size,
            successfulConfigs = configResults.count { it.status == "OK" },
            failedConfigs = configResults.count { it.status == "FAILED" },
            totalJars = jarResults.size,
            successfulJars = jarResults.count { it.status == "OK" },
            failedJars = jarResults.count { it.status == "FAILED" },
            configs = configResults.sortedBy { it.sourceName },
            jars = jarResults.sortedBy { it.sourceConfig },
        )

        val manifestFile = outputDir.resolve("manifest.json")
        Files.writeString(manifestFile, gson.toJson(manifest), StandardCharsets.UTF_8)
        println()
        println("Download complete:")
        println("  Configs: ${manifest.successfulConfigs} OK / ${manifest.failedConfigs} failed / ${manifest.totalConfigs} total")
        println("  JARs:    ${manifest.successfulJars} OK / ${manifest.failedJars} failed / ${manifest.totalJars} total")
        println("  Manifest: $manifestFile")
        return manifest
    }

    private fun downloadConfig(
        source: DesktopDepotSource,
        loader: DesktopResourceLoader,
        configsDir: Path,
        jarsDir: Path,
        configResults: MutableSet<DownloadedConfig>,
        jarResults: MutableSet<DownloadedJar>,
        downloadedJarUrls: MutableSet<String>,
    ) {
        val url = ghproxyUrl(source.location)
        val bytes = try {
            loader.load(url)
        } catch (e: Exception) {
            configResults.add(
                DownloadedConfig(source.name, source.location, "FAILED", e.message ?: e.javaClass.simpleName),
            )
            return
        }

        val sha256 = Digests.sha256Hex(bytes)
        val safeName = sanitizeFileName(source.name)
        val configFile = configsDir.resolve("$safeName.json")
        Files.write(configFile, bytes)

        val root = try {
            JsonParser.parseString(bytes.toString(StandardCharsets.UTF_8)).asJsonObject
        } catch (e: Exception) {
            configResults.add(
                DownloadedConfig(source.name, source.location, "FAILED", "JSON parse error: ${e.message}"),
            )
            return
        }

        val sites = extractSites(root)
        val jarUrls = extractJarUrls(root, sites)
        val cloudProviderCount = countCloudProviderSites(sites)

        jarUrls.forEach { (jarUrl, referencingSites) ->
            if (downloadedJarUrls.add(jarUrl)) {
                downloadJar(source.name, jarUrl, referencingSites, loader, jarsDir, jarResults)
            }
        }

        configResults.add(
            DownloadedConfig(
                sourceName = source.name,
                sourceUrl = source.location,
                status = "OK",
                siteCount = sites.size,
                jarCount = jarUrls.size,
                cloudProviderSites = cloudProviderCount,
                jsonBytes = bytes.size,
                sha256 = sha256,
            ),
        )
    }

    private fun downloadJar(
        sourceConfig: String,
        jarUrl: String,
        referencingSites: List<String>,
        loader: DesktopResourceLoader,
        jarsDir: Path,
        jarResults: MutableSet<DownloadedJar>,
    ) {
        val url = ghproxyUrl(jarUrl)
        val bytes = try {
            loader.load(url)
        } catch (e: Exception) {
            jarResults.add(
                DownloadedJar(sourceConfig, jarUrl, "FAILED", e.message ?: e.javaClass.simpleName),
            )
            return
        }

        val sha256 = Digests.sha256Hex(bytes)
        val safeName = sanitizeFileName(URI(url).path.substringAfterLast('/').ifBlank { "unknown.jar" })
        val jarFile = jarsDir.resolve("${sha256.take(8)}_$safeName")
        Files.write(jarFile, bytes)

        jarResults.add(
            DownloadedJar(
                sourceConfig = sourceConfig,
                jarUrl = jarUrl,
                status = "OK",
                bytes = bytes.size,
                sha256 = sha256,
                referencedBySites = referencingSites,
            ),
        )
    }

    data class SiteInfo(
        val key: String,
        val name: String,
        val api: String,
        val jar: String,
        val type: Int,
        val ext: String,
        val header: Map<String, String>,
    )

    private fun extractSites(root: JsonObject): List<SiteInfo> {
        val sitesArray = root.get("sites")?.takeIf(JsonElement::isJsonArray)?.asJsonArray ?: return emptyList()
        return sitesArray.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val obj = element.asJsonObject
            SiteInfo(
                key = str(obj, "key"),
                name = str(obj, "name"),
                api = str(obj, "api"),
                jar = str(obj, "jar"),
                type = obj.get("type")?.takeIf(JsonElement::isJsonPrimitive)?.asInt ?: 0,
                ext = str(obj, "ext"),
                header = extractHeader(obj),
            )
        }
    }

    private fun extractJarUrls(root: JsonObject, sites: List<SiteInfo>): Map<String, List<String>> {
        val result = mutableMapOf<String, MutableList<String>>()
        val rootSpider = str(root, "spider").ifBlank { "" }
        if (rootSpider.isNotBlank() && rootSpider.startsWith("http")) {
            result.getOrPut(rootSpider) { mutableListOf() }
        }
        for (site in sites) {
            val jarUrl = site.jar.ifBlank { rootSpider }
            if (jarUrl.isNotBlank() && jarUrl.startsWith("http")) {
                result.getOrPut(jarUrl) { mutableListOf() }.add(site.key)
            }
        }
        return result
    }

    private fun countCloudProviderSites(sites: List<SiteInfo>): Int {
        val cloudPatterns = listOf(
            "alipan", "aliyun", "aliyundrive",
            "quark", "ucpan", "ucloud",
            "baidu", "baidupan", "baiduwp",
            "123pan", "123cloud",
            "tianyi", "cloud189", "189",
            "webdav",
            "115", "115pan",
            "wenshushu", "wss",
            "yandex",
        )
        return sites.count { site ->
            val combined = (site.api + " " + site.name + " " + site.ext).lowercase()
            cloudPatterns.any { combined.contains(it) }
        }
    }

    private fun extractHeader(obj: JsonObject): Map<String, String> {
        val headerEl = obj.get("header") ?: return emptyMap()
        if (!headerEl.isJsonObject) return emptyMap()
        return headerEl.asJsonObject.entrySet().associate { (k, v) ->
            k to (v.takeIf(JsonElement::isJsonPrimitive)?.asString ?: "")
        }
    }

    private fun str(obj: JsonObject, key: String): String =
        obj.get(key)?.takeIf(JsonElement::isJsonPrimitive)?.asString?.trim().orEmpty()

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9_\\-]"), "_").take(64).ifBlank { "unknown" }
}
