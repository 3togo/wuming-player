package com.wuming.tv.desktop.tools

import com.wuming.tv.core.catalog.CatalogItemKind
import com.wuming.tv.core.catalog.CatalogSourceExecutor
import com.wuming.tv.core.runtime.guardspider.GuardedSpiderAbi
import com.wuming.tv.desktop.config.DesktopTvBoxConfigurationLoader
import com.wuming.tv.desktop.jvmspider.GuardedDesktopSupportPolicy
import com.wuming.tv.desktop.jvmspider.GuardedSourceActivator
import com.google.gson.Gson
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Logger

/**
 * Developer tool: collects raw episode names from the 饭太硬 guarded desktop sources
 * so episode-name redundancy reduction (quality/source word stripping) can be
 * designed from real data instead of guesses.
 *
 * Downloads and fingerprints the guarded artifact first (no execution), prints the
 * SHA-256, then activates the providers in isolated child JVMs exactly like the
 * desktop UI does, samples catalogue pages, and dumps every episode name.
 *
 * Run:  ./gradlew :desktop:collectEpisodeNames [-Pargs="--all"]
 * Output: episode_names_report.json + episode_names_long.txt in the working directory.
 */
object EpisodeNameCollector {

    private val logger = Logger.getLogger("tvbox.tools.episode-names")
    private val gson = Gson()

    private const val LONG_NAME_THRESHOLD = 12
    private const val MAX_TYPES_PER_SITE = 4
    private const val MAX_PAGES_PER_TYPE = 2
    private const val MAX_DETAILS_PER_SITE = 40

    data class EpisodeNameRecord(
        val site: String,
        val vod: String,
        val line: String,
        val name: String,
    )

    data class Report(
        val configUrl: String,
        val thresholdLong: Int = LONG_NAME_THRESHOLD,
        val sites: Map<String, SiteStats>,
        val totalEpisodeNames: Int,
        val uniqueEpisodeNames: Int,
        val longEpisodeNames: Int,
        val topFrequentNames: List<NameCount>,
        val longNameSamples: List<EpisodeNameRecord>,
    )

    data class SiteStats(val total: Int, val long: Int, val error: String = "")

    data class NameCount(val name: String, val count: Int)

    fun run(configUrl: String, includeUnverified: Boolean, outputDir: Path) {
        logger.info("loading configuration $configUrl includeUnverified=$includeUnverified")
        val configuration = DesktopTvBoxConfigurationLoader().load(configUrl)
        val activator = GuardedSourceActivator()
        val candidates = configuration.sites.filter { site ->
            val execution = site.jvmExecution ?: return@filter false
            site.browseEnabled && activator.isCandidate(execution) &&
                (includeUnverified || GuardedDesktopSupportPolicy.classify(execution).activationAllowed)
        }
        require(candidates.isNotEmpty()) {
            "No guarded desktop candidates found in the configuration."
        }
        logger.info("guarded candidates=${candidates.map { it.key }}")

        val records = mutableListOf<EpisodeNameRecord>()
        val errors = mutableMapOf<String, String>()
        candidates.groupBy { it.jvmExecution!!.artifactLocation }.forEach { (artifactLocation, sites) ->
            val spec = sites.first().jvmExecution!!
            logger.info("inspecting artifact $artifactLocation (download only, no execution)")
            val fingerprint = activator.inspect(spec)
            println("Guarded artifact sha256=${fingerprint.sha256} bytes=${fingerprint.bytes} abis=${fingerprint.supportedAbis}")
            val abi = GuardedSpiderAbi.values().firstOrNull { it.configName in fingerprint.supportedAbis }
                ?: error("Guarded package has no supported ABI")
            val specs = sites.map { it.jvmExecution!! }
            val executors = activator.activateAll(specs, fingerprint.sha256, abi)
            try {
                for (executor in executors) {
                    val siteRecords = collectSite(executor)
                    errors[executor.source.key] = siteRecords.error
                    records.addAll(siteRecords.records)
                    println(
                        "${executor.source.name}: ${siteRecords.records.size} episode names" +
                            (siteRecords.error.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""),
                    )
                }
            } finally {
                executors.forEach { executor ->
                    runCatching { (executor as? AutoCloseable)?.close() }
                }
            }
        }

        writeReport(configUrl, records, errors, outputDir)
    }

    private class SiteResult(val records: List<EpisodeNameRecord>, val error: String)

    private fun collectSite(executor: CatalogSourceExecutor): SiteResult {
        val records = mutableListOf<EpisodeNameRecord>()
        try {
            val types = executor.browseHome().types.take(MAX_TYPES_PER_SITE)
            val seenIds = LinkedHashSet<String>()
            for (type in types) {
                for (page in 1..MAX_PAGES_PER_TYPE) {
                    val result = executor.category(type.id, page)
                    result.items
                        .filter { it.kind == CatalogItemKind.MEDIA && it.id.isNotBlank() }
                        .forEach { seenIds.add(it.id) }
                    if (!result.hasNext) break
                }
            }
            for (id in seenIds.take(MAX_DETAILS_PER_SITE)) {
                val detail = runCatching { executor.detail(id) }.getOrElse { failure ->
                    logger.warning("detail failed site=${executor.source.key} id=$id error=${failure.message}")
                    continue
                }
                val vod = detail.item.name
                for (line in detail.lines) {
                    for (episode in line.episodes) {
                        val name = episode.name.trim()
                        if (name.isNotEmpty()) {
                            records.add(EpisodeNameRecord(executor.source.key, vod, line.name, name))
                        }
                    }
                }
            }
            return SiteResult(records, "")
        } catch (failure: Exception) {
            logger.warning("site scan failed key=${executor.source.key} error=${failure.message}")
            return SiteResult(records, failure.message ?: failure.javaClass.simpleName)
        }
    }

    private fun writeReport(configUrl: String, records: List<EpisodeNameRecord>, errors: Map<String, String>, outputDir: Path) {
        if (records.isEmpty()) {
            println("No episode names collected.")
            return
        }
        val counts = records.groupingBy { it.name }.eachCount()
        val long = records.filter { it.name.length > LONG_NAME_THRESHOLD }
        val report = Report(
            configUrl = configUrl,
            sites = records.groupBy { it.site }.mapValues { (site, list) ->
                SiteStats(
                    total = list.size,
                    long = list.count { it.name.length > LONG_NAME_THRESHOLD },
                    error = errors[site].orEmpty(),
                )
            },
            totalEpisodeNames = records.size,
            uniqueEpisodeNames = counts.size,
            longEpisodeNames = long.size,
            topFrequentNames = counts.entries.sortedByDescending { it.value }.take(100)
                .map { NameCount(it.key, it.value) },
            longNameSamples = long.take(5000),
        )
        val json = outputDir.resolve("episode_names_report.json")
        Files.writeString(json, gson.toJson(report), StandardCharsets.UTF_8)
        val txt = outputDir.resolve("episode_names_long.txt")
        Files.write(txt, long.take(5000).map { it.name }, StandardCharsets.UTF_8)
        println(
            "Collected ${records.size} episode names " +
                "(${counts.size} unique, ${long.size} longer than $LONG_NAME_THRESHOLD chars)",
        )
        println("Report: $json")
        println("Long names: $txt")
    }
}

fun main(args: Array<String>) {
    val configUrl = args.firstOrNull { !it.startsWith("--") }
        ?: "http://www.饭太硬.cc/tv"
    val includeUnverified = "--all" in args
    EpisodeNameCollector.run(configUrl, includeUnverified, Path.of("").toAbsolutePath())
}
