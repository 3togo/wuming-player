package com.wuming.tv.desktop.tools

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.logging.Level
import java.util.logging.Logger
import java.util.zip.ZipEntry

/**
 * Analyzes downloaded plugin configurations and JAR artifacts to extract
 * patterns relevant to OpenDrive robustness improvement.
 *
 * Focus areas:
 *   - Cloud drive provider detection and API patterns
 *   - Authentication / header strategies used by popular plugins
 *   - Error handling and fallback patterns
 *   - JAR spider class structure and implementation patterns
 *   - Configuration schema variations across the ecosystem
 */
object PluginAnalyzer {

    private val logger = Logger.getLogger("tvbox.tools.plugin-analyzer")
    private val gson = Gson()

    enum class CloudProvider(val displayName: String, val patterns: List<String>) {
        ALIPAN("阿里云盘", listOf("alipan", "aliyun", "aliyundrive", "ali")),
        QUARK("夸克网盘", listOf("quark", "夸克")),
        UC("UC网盘", listOf("ucpan", "ucloud", "ucdrive", "优汐")),
        BAIDU("百度网盘", listOf("baidu", "baidupan", "baiduwp", "百度")),
        PAN123("123云盘", listOf("123pan", "123cloud", "123")),
        TIANYI("天翼云盘", listOf("tianyi", "cloud189", "189", "天翼")),
        PAN115("115网盘", listOf("115pan", "115", "115drive")),
        WENSHUSHU("文叔叔", listOf("wenshushu", "wss")),
        WEBDAV("WebDAV", listOf("webdav")),
        YANDEX("Yandex", listOf("yandex")),
        ONEDRIVE("OneDrive", listOf("onedrive", "one_drive")),
        GOOGLE("Google Drive", listOf("googledrive", "gdrive")),
        MEGA("Mega", listOf("mega")),
        PIKPAK("PikPak", listOf("pikpak")),
        XUNLEI("迅雷云盘", listOf("xunlei", "迅雷", "pan.xunlei")),
        MOFUN("魔芬云盘", listOf("mofun")),
    }

    data class SiteAnalysis(
        val key: String,
        val name: String,
        val api: String,
        val type: Int,
        val runtime: String,
        val cloudProvider: CloudProvider?,
        val headerKeys: List<String>,
        val hasExt: Boolean,
        val extType: String,
        val searchable: Boolean,
    )

    data class ConfigAnalysis(
        val sourceName: String,
        val sourceUrl: String,
        val totalSites: Int,
        val sites: List<SiteAnalysis>,
        val cloudProviderDistribution: Map<CloudProvider, Int>,
        val typeDistribution: Map<Int, Int>,
        val runtimeDistribution: Map<String, Int>,
        val rootSpider: String,
        val headerPatterns: Map<String, Int>,
        val extPatterns: Map<String, Int>,
    )

    data class JarAnalysis(
        val fileName: String,
        val sha256: String,
        val bytes: Int,
        val classCount: Int,
        val spiderClasses: List<String>,
        val hasSpiderInterface: Boolean,
        val packageNames: List<String>,
        val cloudRelatedClasses: List<String>,
        val usesOkHttp: Boolean,
        val usesGson: Boolean,
        val usesJsEngine: Boolean,
    )

    data class AnalysisReport(
        val configs: List<ConfigAnalysis>,
        val jars: List<JarAnalysis>,
        val totalSites: Int,
        val totalCloudProviderSites: Int,
        val cloudProviderDistribution: Map<CloudProvider, Int>,
        val typeDistribution: Map<Int, Int>,
        val runtimeDistribution: Map<String, Int>,
        val commonHeaderPatterns: Map<String, Int>,
        val commonExtPatterns: Map<String, Int>,
        val jarSummary: JarSummary,
        val openDriveFindings: List<OpenDriveFinding>,
    )

    data class JarSummary(
        val totalJars: Int,
        val avgClassCount: Double,
        val totalSpiderClasses: Int,
        val jarsWithCloudClasses: Int,
        val jarsUsingOkHttp: Int,
        val jarsUsingGson: Int,
        val jarsUsingJsEngine: Int,
    )

    data class OpenDriveFinding(
        val category: String,
        val severity: String,
        val title: String,
        val description: String,
        val recommendation: String,
        val affectedProviders: List<String>,
    )

    fun run(outputDir: Path): AnalysisReport {
        val configsDir = outputDir.resolve("configs")
        val jarsDir = outputDir.resolve("jars")

        logger.info("analyzing plugins in $outputDir")
        println("Plugin Analyzer")
        println("  Configs dir: $configsDir")
        println("  JARs dir: $jarsDir")
        println()

        val configAnalyses = analyzeConfigs(configsDir)
        val jarAnalyses = analyzeJars(jarsDir)

        val totalSites = configAnalyses.sumOf { it.totalSites }
        val totalCloudProviderSites = configAnalyses.sumOf { it.cloudProviderDistribution.values.sum() }

        val cloudProviderDist = configAnalyses
            .flatMap { it.cloudProviderDistribution.entries }
            .groupBy({ it.key }, { it.value })
            .mapValues { it.value.sum() }
            .toList().sortedByDescending { it.second }.toMap()

        val typeDist = configAnalyses
            .flatMap { it.typeDistribution.entries }
            .groupBy({ it.key }, { it.value })
            .mapValues { it.value.sum() }
            .toList().sortedByDescending { it.second }.toMap()

        val runtimeDist = configAnalyses
            .flatMap { it.runtimeDistribution.entries }
            .groupBy({ it.key }, { it.value })
            .mapValues { it.value.sum() }
            .toList().sortedByDescending { it.second }.toMap()

        val headerPatterns = configAnalyses
            .flatMap { it.headerPatterns.entries }
            .groupBy({ it.key }, { it.value })
            .mapValues { it.value.sum() }
            .toList().sortedByDescending { it.second }.toMap()

        val extPatterns = configAnalyses
            .flatMap { it.extPatterns.entries }
            .groupBy({ it.key }, { it.value })
            .mapValues { it.value.sum() }
            .toList().sortedByDescending { it.second }.toMap()

        val jarSummary = JarSummary(
            totalJars = jarAnalyses.size,
            avgClassCount = if (jarAnalyses.isEmpty()) 0.0 else jarAnalyses.map { it.classCount }.average(),
            totalSpiderClasses = jarAnalyses.sumOf { it.spiderClasses.size },
            jarsWithCloudClasses = jarAnalyses.count { it.cloudRelatedClasses.isNotEmpty() },
            jarsUsingOkHttp = jarAnalyses.count { it.usesOkHttp },
            jarsUsingGson = jarAnalyses.count { it.usesGson },
            jarsUsingJsEngine = jarAnalyses.count { it.usesJsEngine },
        )

        val findings = generateOpenDriveFindings(configAnalyses, jarAnalyses, cloudProviderDist, headerPatterns)

        val report = AnalysisReport(
            configs = configAnalyses,
            jars = jarAnalyses,
            totalSites = totalSites,
            totalCloudProviderSites = totalCloudProviderSites,
            cloudProviderDistribution = cloudProviderDist,
            typeDistribution = typeDist,
            runtimeDistribution = runtimeDist,
            commonHeaderPatterns = headerPatterns,
            commonExtPatterns = extPatterns,
            jarSummary = jarSummary,
            openDriveFindings = findings,
        )

        val reportFile = outputDir.resolve("analysis_report.json")
        Files.writeString(reportFile, gson.toJson(report), StandardCharsets.UTF_8)
        println("Analysis complete:")
        println("  Total sites analyzed: $totalSites")
        println("  Cloud provider sites: $totalCloudProviderSites")
        println("  JARs analyzed: ${jarAnalyses.size}")
        println("  OpenDrive findings: ${findings.size}")
        println("  Report: $reportFile")
        return report
    }

    private fun analyzeConfigs(configsDir: Path): List<ConfigAnalysis> {
        if (!Files.isDirectory(configsDir)) return emptyList()
        return Files.list(configsDir).filter { it.toString().endsWith(".json") }.map { file ->
            analyzeConfig(file)
        }.toList().sortedBy { it.sourceName }
    }

    private fun analyzeConfig(file: Path): ConfigAnalysis {
        val content = try {
            Files.readString(file, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            String(Files.readAllBytes(file), Charsets.ISO_8859_1)
        }
        val root = try {
            JsonParser.parseString(content).asJsonObject
        } catch (e: Exception) {
            return ConfigAnalysis(
                sourceName = file.fileName.toString(),
                sourceUrl = "",
                totalSites = 0,
                sites = emptyList(),
                cloudProviderDistribution = emptyMap(),
                typeDistribution = emptyMap(),
                runtimeDistribution = emptyMap(),
                rootSpider = "",
                headerPatterns = emptyMap(),
                extPatterns = emptyMap(),
            )
        }

        val rootSpider = str(root, "spider")
        val sitesArray = root.get("sites")?.takeIf(JsonElement::isJsonArray)?.asJsonArray
        val sites = sitesArray?.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val obj = element.asJsonObject
            val api = str(obj, "api")
            val name = str(obj, "name")
            val type = obj.get("type")?.takeIf(JsonElement::isJsonPrimitive)?.asInt ?: 0
            val ext = str(obj, "ext")
            val header = extractHeaderKeys(obj)
            val runtime = classifyRuntime(type, api, ext)
            val cloudProvider = detectCloudProvider(api, name, ext)
            SiteAnalysis(
                key = str(obj, "key"),
                name = name,
                api = api,
                type = type,
                runtime = runtime,
                cloudProvider = cloudProvider,
                headerKeys = header,
                hasExt = ext.isNotBlank(),
                extType = classifyExt(ext),
                searchable = obj.get("searchable")?.takeIf(JsonElement::isJsonPrimitive)?.asInt != 0,
            )
        } ?: emptyList()

        val cloudDist = sites.mapNotNull { it.cloudProvider }.groupingBy { it }.eachCount()
        val typeDist = sites.groupingBy { it.type }.eachCount()
        val runtimeDist = sites.groupingBy { it.runtime }.eachCount()
        val headerPatterns = sites.flatMap { it.headerKeys }.groupingBy { it }.eachCount()
        val extPatterns = sites.groupingBy { it.extType }.eachCount()

        return ConfigAnalysis(
            sourceName = file.fileName.toString(),
            sourceUrl = "",
            totalSites = sites.size,
            sites = sites,
            cloudProviderDistribution = cloudDist,
            typeDistribution = typeDist,
            runtimeDistribution = runtimeDist,
            rootSpider = rootSpider,
            headerPatterns = headerPatterns,
            extPatterns = extPatterns,
        )
    }

    private fun analyzeJars(jarsDir: Path): List<JarAnalysis> {
        if (!Files.isDirectory(jarsDir)) return emptyList()
        return Files.list(jarsDir).filter { it.toString().endsWith(".jar") || it.toString().endsWith(".dex") }.map { file ->
            analyzeJar(file)
        }.toList().sortedBy { it.fileName }
    }

    private fun analyzeJar(file: Path): JarAnalysis {
        val bytes = Files.size(file).toInt()
        val fileName = file.fileName.toString()
        val sha256 = file.fileName.toString().substringBefore("_").take(8)

        val entries = mutableMapOf<String, Long>()
        try {
            JarFile(file.toFile()).use { jar ->
                val iter = jar.entries()
                while (iter.hasMoreElements()) {
                    val entry: ZipEntry = iter.nextElement()
                    entries[entry.name] = entry.size
                }
            }
        } catch (e: Exception) {
            logger.log(Level.FINE, "jar inspection failed: $fileName", e)
        }

        val classEntries = entries.keys.filter { it.endsWith(".class") }
        val spiderClasses = classEntries.filter {
            it.contains("Spider", ignoreCase = true) || it.contains("Crawler", ignoreCase = true)
        }.map { it.replace("/", ".").removeSuffix(".class") }

        val packageNames = classEntries
            .map { it.substringBeforeLast("/").replace("/", ".") }
            .distinct()
            .sorted()

        val cloudRelatedClasses = classEntries.filter { name ->
            CloudProvider.entries.any { provider ->
                provider.patterns.any { pattern -> name.contains(pattern, ignoreCase = true) }
            }
        }.map { it.replace("/", ".").removeSuffix(".class") }

        val usesOkHttp = entries.keys.any { it.contains("okhttp", ignoreCase = true) }
        val usesGson = entries.keys.any { it.contains("gson", ignoreCase = true) }
        val usesJsEngine = entries.keys.any {
            it.contains("rhino", ignoreCase = true) ||
                it.contains("graal", ignoreCase = true) ||
                it.contains("quickjs", ignoreCase = true) ||
                it.contains("jsengine", ignoreCase = true)
        }

        return JarAnalysis(
            fileName = fileName,
            sha256 = sha256,
            bytes = bytes,
            classCount = classEntries.size,
            spiderClasses = spiderClasses,
            hasSpiderInterface = spiderClasses.isNotEmpty(),
            packageNames = packageNames,
            cloudRelatedClasses = cloudRelatedClasses,
            usesOkHttp = usesOkHttp,
            usesGson = usesGson,
            usesJsEngine = usesJsEngine,
        )
    }

    private fun detectCloudProvider(api: String, name: String, ext: String): CloudProvider? {
        val combined = "$api $name $ext".lowercase()
        return CloudProvider.entries.firstOrNull { provider ->
            provider.patterns.any { pattern -> combined.contains(pattern) }
        }
    }

    private fun classifyRuntime(type: Int, api: String, ext: String): String = when (type) {
        0 -> "DIRECT_HTTP"
        1 -> "JAR_SPIDER"
        3 -> if (api.endsWith(".js") || ext.endsWith(".js")) "DIRECT_JAVASCRIPT" else "UNKNOWN_TYPE_3"
        4 -> "REMOTE_SPIDER_HTTP"
        else -> "UNKNOWN_TYPE_$type"
    }

    private fun classifyExt(ext: String): String = when {
        ext.isBlank() -> "NONE"
        ext.startsWith("http") -> "URL"
        ext.endsWith(".js") -> "JS_FILE"
        ext.startsWith("{") || ext.startsWith("[") -> "JSON_INLINE"
        ext.contains("base64", ignoreCase = true) || ext.matches(Regex("^[A-Za-z0-9+/=]{20,}$")) -> "BASE64"
        else -> "OPAQUE"
    }

    private fun extractHeaderKeys(obj: JsonObject): List<String> {
        val headerEl = obj.get("header") ?: return emptyList()
        if (!headerEl.isJsonObject) return emptyList()
        return headerEl.asJsonObject.keySet().toList()
    }

    private fun generateOpenDriveFindings(
        configs: List<ConfigAnalysis>,
        jars: List<JarAnalysis>,
        cloudDist: Map<CloudProvider, Int>,
        headerPatterns: Map<String, Int>,
    ): List<OpenDriveFinding> {
        val findings = mutableListOf<OpenDriveFinding>()

        // Finding 1: Cloud provider coverage
        val detectedProviders = cloudDist.keys
        val supportedProviders = setOf(
            CloudProvider.ALIPAN, CloudProvider.QUARK, CloudProvider.UC,
            CloudProvider.BAIDU, CloudProvider.PAN123, CloudProvider.TIANYI, CloudProvider.WEBDAV,
        )
        val unsupportedProviders = detectedProviders - supportedProviders
        if (unsupportedProviders.isNotEmpty()) {
            findings.add(
                OpenDriveFinding(
                    category = "COVERAGE",
                    severity = "MEDIUM",
                    title = "Unsupported cloud providers detected in popular plugins",
                    description = "The following cloud providers appear in popular plugin configurations but are not supported by OpenDrive: " +
                        unsupportedProviders.joinToString { it.displayName },
                    recommendation = "Consider adding OpenDriveProvider implementations for these providers to expand coverage.",
                    affectedProviders = unsupportedProviders.map { it.displayName },
                ),
            )
        }

        // Finding 2: Header patterns
        val commonHeaders = headerPatterns.filter { it.value >= 3 }.toList().sortedByDescending { it.second }
        if (commonHeaders.isNotEmpty()) {
            findings.add(
                OpenDriveFinding(
                    category = "AUTH",
                    severity = "INFO",
                    title = "Common header patterns across popular plugins",
                    description = "Headers frequently used: " +
                        commonHeaders.joinToString { "${it.first} (${it.second} sites)" },
                    recommendation = "Ensure OpenDrive providers correctly propagate these headers through the playback chain.",
                    affectedProviders = emptyList(),
                ),
            )
        }

        // Finding 3: JAR spider patterns
        val jarsWithCloudClasses = jars.count { it.cloudRelatedClasses.isNotEmpty() }
        if (jarsWithCloudClasses > 0) {
            findings.add(
                OpenDriveFinding(
                    category = "SPIDER",
                    severity = "INFO",
                    title = "JAR spiders contain cloud-drive-related classes",
                    description = "$jarsWithCloudClasses JAR artifacts contain classes referencing cloud drive providers. " +
                        "These spiders may implement custom cloud drive integration logic.",
                    recommendation = "Study these spider implementations for alternative API endpoints, error recovery patterns, and authentication flows that could improve OpenDrive robustness.",
                    affectedProviders = emptyList(),
                ),
            )
        }

        // Finding 4: Runtime distribution
        val totalSites = configs.sumOf { it.totalSites }
        val jarSpiderSites = configs.sumOf { it.runtimeDistribution["JAR_SPIDER"] ?: 0 }
        if (jarSpiderSites > totalSites * 0.3) {
            findings.add(
                OpenDriveFinding(
                    category = "ARCHITECTURE",
                    severity = "LOW",
                    title = "High proportion of JAR spider sites",
                    description = "$jarSpiderSites out of $totalSites sites use JAR spider runtime. " +
                        "These sites rely on external JAR artifacts that may contain cloud drive integration code.",
                    recommendation = "Ensure OpenDrive's fallback path handles JAR spider failures gracefully, as these are the most common source type.",
                    affectedProviders = emptyList(),
                ),
            )
        }

        // Finding 5: Sites without headers
        val sitesWithoutHeaders = configs.flatMap { it.sites }.count { it.headerKeys.isEmpty() && it.cloudProvider != null }
        if (sitesWithoutHeaders > 0) {
            findings.add(
                OpenDriveFinding(
                    category = "AUTH",
                    severity = "HIGH",
                    title = "Cloud provider sites without custom headers",
                    description = "$sitesWithoutHeaders cloud provider sites do not specify custom headers in their configuration. " +
                        "This may indicate reliance on cookies or tokens passed through other mechanisms.",
                    recommendation = "Ensure OpenDrive's token management correctly handles providers that don't use configuration-level headers. " +
                        "The OpenDriveTokenManager should be robust to missing header configurations.",
                    affectedProviders = emptyList(),
                ),
            )
        }

        // Finding 6: Multiple cloud providers per config
        val multiProviderConfigs = configs.filter { it.cloudProviderDistribution.size > 1 }
        if (multiProviderConfigs.isNotEmpty()) {
            findings.add(
                OpenDriveFinding(
                    category = "ARCHITECTURE",
                    severity = "MEDIUM",
                    title = "Configurations with multiple cloud providers",
                    description = "${multiProviderConfigs.size} configurations contain sites from multiple cloud providers. " +
                        "This means users frequently need to authenticate with multiple providers simultaneously.",
                    recommendation = "Ensure OpenDrive's credential management supports concurrent multi-provider authentication without token cross-contamination.",
                    affectedProviders = emptyList(),
                ),
            )
        }

        // Finding 7: JAR artifacts using JS engines
        val jarsWithJs = jars.count { it.usesJsEngine }
        if (jarsWithJs > 0) {
            findings.add(
                OpenDriveFinding(
                    category = "SPIDER",
                    severity = "LOW",
                    title = "JAR spiders with embedded JS engines",
                    description = "$jarsWithJs JAR artifacts contain embedded JavaScript engine classes (Rhino/Graal/QuickJS). " +
                        "These spiders may execute JavaScript for cloud drive API interactions.",
                    recommendation = "OpenDrive should be prepared to handle playback results from JS-executing spiders, which may produce non-standard URL formats or require JS-specific header handling.",
                    affectedProviders = emptyList(),
                ),
            )
        }

        // Finding 8: Type 0 direct HTTP sites with cloud providers
        val directHttpCloudSites = configs.flatMap { it.sites }
            .filter { it.type == 0 && it.cloudProvider != null }
        if (directHttpCloudSites.isNotEmpty()) {
            findings.add(
                OpenDriveFinding(
                    category = "ARCHITECTURE",
                    severity = "INFO",
                    title = "Direct HTTP cloud provider sites",
                    description = "${directHttpCloudSites.size} sites use type 0 (direct HTTP) with cloud provider APIs. " +
                        "These sites may use MacCMS-style APIs that proxy cloud drive content.",
                    recommendation = "OpenDrive should handle cases where cloud drive content is proxied through MacCMS APIs, which may strip original authentication headers.",
                    affectedProviders = emptyList(),
                ),
            )
        }

        return findings
    }

    private fun str(obj: JsonObject, key: String): String =
        obj.get(key)?.takeIf(JsonElement::isJsonPrimitive)?.asString?.trim().orEmpty()
}
