package com.wuming.tv.desktop

import com.wuming.tv.desktop.ui.DesktopWindow
import com.wuming.tv.desktop.ui.DesktopTvTheme
import com.wuming.tv.desktop.diagnostics.DesktopLogging
import com.wuming.tv.desktop.config.DesktopTvBoxConfigurationLoader
import com.wuming.tv.desktop.cloud.DesktopCloudAccounts
import com.wuming.tv.core.runtime.js.GraalJsCatalogSourceExecutor
import com.wuming.tv.core.runtime.jvmspider.JvmCatVodCatalogSourceExecutor
import com.wuming.tv.core.runtime.guardspider.GuardedSpiderAbi
import com.wuming.tv.core.runtime.guardspider.GuardedSpiderRuntime
import com.wuming.tv.core.runtime.guardspider.GuardedSpiderPreparer
import com.wuming.tv.core.runtime.guardspider.GuardedCatVodCatalogSourceExecutor
import com.wuming.tv.core.runtime.guardspider.GuardedCatVodSiteSpec
import com.wuming.tv.core.runtime.guardspider.GuardedSpiderDiagnosticInstrumenter
import com.wuming.tv.core.runtime.guardspider.GuardedRuntimeAssetsProducer
import com.wuming.tv.core.cloud.CloudDriveDetector
import com.wuming.tv.core.catalog.CatalogFilterSelectionPolicy
import com.wuming.tv.core.catalog.CatalogHome
import com.wuming.tv.desktop.jvmspider.TrustedJvmSpiderArtifact
import com.wuming.tv.desktop.jvmspider.SpiderArtifactFingerprinter
import com.wuming.tv.desktop.live.DesktopLiveSourceSmoke
import com.wuming.tv.desktop.net.DesktopProxyScanner
import com.wuming.tv.desktop.net.DesktopProxySettings
import com.wuming.tv.desktop.net.DesktopResourceLoader
import com.wuming.tv.desktop.player.HlsCompatibilityPreparer
import com.wuming.tv.desktop.tools.PluginBatchDownloader
import com.wuming.tv.desktop.tools.PluginAnalyzer
import com.wuming.tv.desktop.tools.PluginAnalysisReport
import java.awt.GraphicsEnvironment
import java.net.URI
import java.nio.charset.StandardCharsets
import javax.swing.SwingUtilities

fun main(args: Array<String>) {
    if ("--lan-relay" in args) {
        val relay = com.wuming.tv.desktop.server.DesktopLanRelay()
        relay.enable()
        Runtime.getRuntime().addShutdownHook(Thread { relay.close() })
        println("LAN relay running at ${relay.address}. No playlists are shared.")
        if ("--pair" in args) println("Pairing code: ${relay.pairingCode()} (valid for two minutes)")
        java.util.concurrent.CountDownLatch(1).await()
        return
    }
    DesktopLogging.initialize()
    // Apply a previously scanned proxy unless overridden on the command line.
    DesktopProxySettings.persistedProxy()?.let { persisted ->
        if (System.getProperty("tvbox.ghproxy") == null) System.setProperty("tvbox.ghproxy", persisted)
    }
    if ("--test-libvio" in args) {
        val index = args.indexOf("--test-libvio")
        val executor = com.wuming.tv.desktop.catalog.DesktopLibvio.executor(
            com.wuming.tv.core.libvio.LibvioSiteSpec("builtin_libvio", "Libvio"))
        try {
            val home = executor.browseHome()
            require(home.items.isNotEmpty() && home.types.isNotEmpty()) { "Libvio Home returned no items or categories" }
            val category = executor.category(home.types.first().id, 1)
            require(category.items.isNotEmpty()) { "Libvio category returned no items" }
            val secondPage = executor.category(home.types.first().id, 2)
            require(secondPage.items.isNotEmpty() && secondPage.items.first().id != category.items.first().id) { "Libvio category pagination did not advance" }
            require(home.items.any { it.picture.isNotBlank() }) { "Libvio Home returned no posters" }
            require(executor.search("wuming_no_such_title_9f8b").isEmpty()) { "Libvio missing-title search returned unfiltered cards" }
            val results = executor.search(args.getOrNull(index + 1) ?: "死神")
            require(results.isNotEmpty()) { "Libvio search returned no items" }
            val detail = executor.detail(results.first().id)
            val line = detail.lines.first()
            val playback = executor.play(line.flag, line.episodes.first().id)
            require(playback.supported) { playback.message }
            println(com.google.gson.Gson().toJson(mapOf(
                "adapter" to "builtin_libvio", "home" to home.items.size, "types" to home.types.size,
                "category" to category.items.size, "categoryPage2" to secondPage.items.size, "emptySearchVerified" to true,
                "posters" to home.items.count { it.picture.isNotBlank() }, "search" to results.size, "episodes" to line.episodes.size,
                "title" to detail.item.name, "playback" to playback)))
        } finally { executor.cancel() }
        return
    }
    val inspectPackagesIndex = args.indexOf("--inspect-packages")
    val inspectIndex = args.indexOf("--inspect-config")
    val testSourceIndex = args.indexOf("--test-js-source")
    val testLiveIndex = args.indexOf("--test-live-source")
    val testJvmSourceIndex = args.indexOf("--test-jvm-spider")
    val fingerprintSpiderIndex = args.indexOf("--fingerprint-spider-artifact")
    val probeGuardedIndex = args.indexOf("--probe-guarded-spider")
    val prepareGuardedIndex = args.indexOf("--prepare-guarded-spider")
    val testGuardedIndex = args.indexOf("--test-guarded-spider")
    val diagnoseGuardedIndex = args.indexOf("--diagnose-guarded-spider")
    val analyzePluginsIndex = args.indexOf("--analyze-plugins")
    val testCloudLoginIndex = args.indexOf("--test-cloud-login")
    val guardedRunIndex = if (diagnoseGuardedIndex >= 0) diagnoseGuardedIndex else testGuardedIndex
    when {
        "--help" in args -> {
            println("TVBox Desktop")
            println("  --allow-multiple-instances  allow an additional GUI for this launch")
            println("  --lan-relay [--pair]  run the private LAN relay without a desktop window")
            println("  --smoke  verify generic-core integration without opening a window")
            println("  --log-directory  print the persistent diagnostic-log directory")
            println("  --inspect-packages PATH  inspect local package files without executing providers")
            println("  --inspect-config URL  locally decode and summarize a TVBox configuration")
            println("  --test-libvio [QUERY]  verify the built-in shared Libvio direct-play adapter")
            println("  --test-js-source URL QUERY [SOURCE_KEY]  test local search/detail/playback")
            println("  --test-live-source URL [PLAYLIST_NAME]  load and parse a configured Live TV playlist")
            println("  --test-jvm-spider URL QUERY SOURCE_KEY SHA256  explicitly trust and test a portable JVM spider")
            println("  --fingerprint-spider-artifact URL SOURCE_KEY  identify and hash a configured spider bundle without executing it")
            println("  --probe-guarded-spider ARTIFACT SHA256 ABI  explicitly trust and probe a guarded ARM spider")
            println("  --prepare-guarded-spider ARTIFACT SHA256 ABI  decrypt and convert once, then reuse a validated cache")
            println("  --test-guarded-spider URL QUERY SOURCE_KEY SHA256 ABI [ARTIFACT]  prepare and test an isolated guarded CatVod source")
            println("  --diagnose-guarded-spider URL QUERY SOURCE_KEY SHA256 ABI [ARTIFACT]  use a separate cached artifact to expose swallowed detail exceptions")
            println("  --test-cloud-login [PROVIDER_ID]  create and poll redacted cloud QR challenges without opening the GUI")
            println("  --analyze-plugins [OUTPUT_DIR]  download all depot.json plugins, analyze them, and generate an OpenDrive robustness report")
            println("  --scan-proxy [--apply]  probe GitHub proxy mirrors and report the fastest; --apply saves it")
        }

        "--log-directory" in args -> println(DesktopLogging.directory)

        "--smoke" in args -> {
            val report = DesktopSmokeCheck.verify()
            println("TVBox Desktop smoke check: OK")
            report.components.forEach { println("- $it") }
        }

        args.indexOf("--scan-proxy") >= 0 -> {
            val apply = "--apply" in args
            val report = DesktopProxyScanner.scan()
            println("GitHub proxy scan (test=${report.testUrl})")
            report.results
                .sortedBy { if (it.success) it.elapsedMs else Int.MAX_VALUE }
                .forEach { result ->
                    val mark = if (result === report.best) "  *" else ""
                    val status = if (result.success) {
                        "${result.elapsedMs}ms (${result.contentBytes} bytes)"
                    } else {
                        "FAIL (${result.error ?: "HTTP ${result.status}"})"
                    }
                    val tag = if (result.candidate.note.isNotBlank()) " [${result.candidate.note}]" else ""
                    println("- ${result.candidate.name}$tag: $status$mark")
                }
            val best = report.best
            if (best == null) {
                println("No working proxy found; direct GitHub access will be used where possible.")
            } else {
                println("Optimal proxy: ${best.candidate.name} (tvbox.ghproxy=${best.recommendedValue})")
                if (apply) {
                    DesktopProxySettings.persist(best.recommendedValue)
                    println("Persisted to ${DesktopProxySettings.path}; restart the app (or pass -Dtvbox.ghproxy=${best.recommendedValue}) to use it.")
                } else {
                    println("To use it now, launch with: -Dtvbox.ghproxy=${best.recommendedValue}")
                }
            }
        }

        testCloudLoginIndex >= 0 -> {
            val requested = args.getOrNull(testCloudLoginIndex + 1)?.takeUnless { it.startsWith("--") }
            val providers = DesktopCloudAccounts().providers.filter { requested == null || it.id() == requested }
            require(providers.isNotEmpty()) { "No desktop cloud account provider matched ${requested.orEmpty()}" }
            providers.forEach { provider ->
                require(provider.available()) { "${provider.displayName()} is unavailable: ${provider.availabilityMessage()}" }
                require(provider.supportsQrLogin()) { "${provider.displayName()} does not support QR login" }
                val challenge = provider.beginLogin()
                require(challenge.content.startsWith("https://")) {
                    "${provider.displayName()} returned a non-HTTPS QR challenge"
                }
                val state = provider.pollLogin(challenge.id)
                println("Cloud QR login: OK")
                println("- provider: ${provider.displayName()} (${provider.id()})")
                println("- challenge: HTTPS, ${challenge.content.length} characters")
                println("- initial state: $state")
            }
        }

        inspectPackagesIndex >= 0 -> {
            val input = java.io.File(requireNotNull(args.getOrNull(inspectPackagesIndex + 1)) { "Expected folder or ZIP" })
            val files = if (input.isDirectory) com.wuming.tv.desktop.packages.PackageImportDialog.readDirectory(input)
                else input.inputStream().use(com.wuming.tv.core.source.packages.SourcePackageInspector::archive)
            val report = com.wuming.tv.core.source.packages.SourcePackageInspector.inspect(files).map { candidate ->
                mapOf("name" to candidate.name, "entry" to candidate.entry, "sites" to candidate.sites.size,
                    "version" to candidate.version, "errors" to candidate.errors, "warnings" to candidate.warnings,
                    "artifacts" to candidate.artifacts)
            }
            println(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report))
        }

        inspectIndex >= 0 -> {
            val location = args.getOrNull(inspectIndex + 1)
                ?: throw IllegalArgumentException("--inspect-config requires a URL or file")
            val config = DesktopTvBoxConfigurationLoader().load(location)
            println("TVBox configuration: OK")
            println("- sites: ${config.sites.size}")
            println("- searchable: ${config.searchableSites.size}")
            println("- live playlists: ${config.lives.size}")
            println("- HLS ad-filter rules: ${config.hlsAdRules.size}")
            println("- direct JavaScript candidates: ${config.directJavaScriptSites.size}")
            println("- MacCMS collection APIs: ${config.macCmsSites.size}")
            println("- portable JVM spider candidates: ${config.jvmSpiderSites.size}")
            println("- validated configuration cache: ${if (config.reusedCache) "reused" else "refreshed"}")
            config.jvmSpiderSites.forEach { site ->
                println("- spider: key=${site.key} · name=${site.name} · class=${site.jvmExecution?.className}")
            }
        }

        testLiveIndex >= 0 -> {
            val location = args.getOrNull(testLiveIndex + 1)
                ?: throw IllegalArgumentException("--test-live-source requires a configuration URL or file")
            val requestedName = args.getOrNull(testLiveIndex + 2)
            val report = DesktopLiveSourceSmoke().verify(location, requestedName)
            println("Live TV source: OK")
            println("- source: ${report.reference.name}")
            println("- groups: ${report.playlist.groups.size}")
            println("- channels: ${report.playlist.channelCount}")
            println("- EPG locations: ${report.playlist.epgUrls.size}")
            if (report.skippedSources.isNotEmpty()) {
                println("- skipped incompatible sources: ${report.skippedSources.joinToString()}")
            }
            report.playlist.groups.asSequence().flatMap { it.channels.asSequence() }.take(5)
                .forEach { println("- ${it.name} · routes=${it.streams.size}") }
        }

        testSourceIndex >= 0 -> {
            val location = args.getOrNull(testSourceIndex + 1)
                ?: throw IllegalArgumentException("--test-js-source requires a configuration URL and query")
            val query = args.getOrNull(testSourceIndex + 2)
                ?: throw IllegalArgumentException("--test-js-source requires a query")
            val requestedSource = args.getOrNull(testSourceIndex + 3)
            val site = DesktopTvBoxConfigurationLoader().load(location).directJavaScriptSites
                .firstOrNull { it.execution != null && (requestedSource == null || it.key == requestedSource) }
                ?: throw IllegalArgumentException("Configuration has no executable direct-JavaScript source")
            GraalJsCatalogSourceExecutor(site.execution!!).use { executor ->
                val home = executor.browseHome()
                val featured = home.items
                val category = home.types.firstOrNull()?.let { type ->
                    executor.category(type.id, 1, CatalogFilterSelectionPolicy.defaults(type))
                }
                val items = executor.search(query)
                println("Local JavaScript source: OK")
                println("- source: ${site.name}")
                println("- home VOD: ${featured.size}")
                println("- media types: ${home.types.size}")
                printFilterSummary(home)
                category?.let { println("- first category VOD: ${it.items.size} · page ${it.page}/${it.pageCount.takeIf { count -> count > 0 } ?: "?"}") }
                println("- results: ${items.size}")
                items.take(5).forEach { println("- ${it.name} · ${it.remarks}") }
                items.firstOrNull()?.let { item ->
                    val detail = executor.detail(item.id)
                    val line = detail.lines.firstOrNull()
                    val episode = line?.episodes?.firstOrNull()
                    println("- playback lines: ${detail.lines.size}")
                    println("- episodes: ${detail.lines.sumOf { it.episodes.size }}")
                    if (line != null && episode != null) {
                        listOfNotNull(
                            line.episodes.firstOrNull(),
                            line.episodes.getOrNull(line.episodes.size / 2),
                            line.episodes.lastOrNull(),
                        ).distinctBy { it.id }.forEach { candidate ->
                            val playback = executor.play(line.flag, candidate.id)
                            println("- playback ${candidate.name}: ${if (playback.supported) "supported" else playback.message}")
                            println("- playback headers: ${playback.headers.size}")
                        }
                    }
                }
            }
        }

        testJvmSourceIndex >= 0 -> {
            val location = args.getOrNull(testJvmSourceIndex + 1)
                ?: throw IllegalArgumentException("--test-jvm-spider requires a configuration URL")
            val query = args.getOrNull(testJvmSourceIndex + 2)
                ?: throw IllegalArgumentException("--test-jvm-spider requires a query")
            val sourceKey = args.getOrNull(testJvmSourceIndex + 3)
                ?: throw IllegalArgumentException("--test-jvm-spider requires a source key")
            val trustedSha256 = args.getOrNull(testJvmSourceIndex + 4)
                ?: throw IllegalArgumentException("--test-jvm-spider requires an explicitly trusted SHA-256")
            val site = DesktopTvBoxConfigurationLoader().load(location).jvmSpiderSites
                .firstOrNull { it.key == sourceKey }?.jvmExecution
                ?: throw IllegalArgumentException("Configuration has no portable JVM candidate named $sourceKey")
            TrustedJvmSpiderArtifact.resolve(site.artifactLocation).use { artifact ->
                JvmCatVodCatalogSourceExecutor(site, artifact.path, trustedSha256).use { executor ->
                    val home = executor.browseHome()
                    val category = home.types.firstOrNull()?.let { type ->
                        runCatching {
                            executor.category(type.id, 1, CatalogFilterSelectionPolicy.defaults(type))
                        }.getOrNull()
                    }
                    val items = executor.search(query)
                    println("Portable JVM spider: OK")
                    println("- source: ${site.name}")
                    println("- artifact SHA-256: ${executor.artifactReport.sha256}")
                    println("- artifact classes: ${executor.artifactReport.classCount}")
                    println("- home VOD: ${home.items.size}")
                    println("- media types: ${home.types.size}")
                    printFilterSummary(home)
                    category?.let { println("- first category VOD: ${it.items.size} · page ${it.page}/${it.pageCount.takeIf { count -> count > 0 } ?: "?"}") }
                    println("- results: ${items.size}")
                    items.take(5).forEach { println("- ${it.name} · ${it.remarks}") }
                    items.firstOrNull()?.let { item ->
                        val detail = executor.detail(item.id)
                        println("- playback lines: ${detail.lines.size}")
                        println("- episodes: ${detail.lines.sumOf { it.episodes.size }}")
                        require(detail.lines.isNotEmpty()) { "Portable JVM detail returned no playback lines" }
                        val candidates = buildList {
                            detail.lines.forEach { line ->
                                line.episodes.firstOrNull()?.let { add(line to it) }
                            }
                            detail.lines.firstOrNull()?.let { line ->
                                line.episodes.getOrNull(line.episodes.size / 2)?.let { add(line to it) }
                                line.episodes.lastOrNull()?.let { add(line to it) }
                            }
                        }.distinctBy { (line, episode) -> "${line.flag}\u0000${episode.id}" }
                        require(candidates.isNotEmpty()) { "Portable JVM detail returned no episodes" }
                        candidates.forEach { (line, candidate) ->
                            val playback = executor.play(line.flag, candidate.id)
                            println("- playback ${line.name}/${candidate.name}: ${if (playback.supported) "supported" else playback.message}")
                            println("- playback headers: ${playback.headers.size}")
                            require(playback.supported) {
                                "Portable JVM playback failed for ${line.name}/${candidate.name}: ${playback.message}"
                            }
                        }
                    }
                }
            }
        }

        fingerprintSpiderIndex >= 0 -> {
            val location = args.getOrNull(fingerprintSpiderIndex + 1)
                ?: throw IllegalArgumentException("--fingerprint-spider-artifact requires a configuration URL")
            val sourceKey = args.getOrNull(fingerprintSpiderIndex + 2)
                ?: throw IllegalArgumentException("--fingerprint-spider-artifact requires a source key")
            val site = DesktopTvBoxConfigurationLoader().load(location).jvmSpiderSites
                .firstOrNull { it.key == sourceKey }?.jvmExecution
                ?: throw IllegalArgumentException("Configuration has no spider source named $sourceKey")
            TrustedJvmSpiderArtifact.resolve(site.artifactLocation).use { artifact ->
                val fingerprint = SpiderArtifactFingerprinter.inspect(artifact.path)
                println("Spider artifact fingerprint: OK (not trusted or executed)")
                println("- source: ${site.name}")
                println("- provider class: ${site.className}")
                println("- SHA-256: ${fingerprint.sha256}")
                println("- bytes: ${fingerprint.bytes}")
                println("- kind: ${fingerprint.kind.name.lowercase()}")
                println("- ABIs: ${fingerprint.supportedAbis.joinToString().ifEmpty { "none" }}")
                println("- classes: ${fingerprint.classCount}")
            }
        }

        probeGuardedIndex >= 0 -> {
            val location = args.getOrNull(probeGuardedIndex + 1)
                ?: throw IllegalArgumentException("--probe-guarded-spider requires an artifact URL or file")
            val trustedSha256 = args.getOrNull(probeGuardedIndex + 2)
                ?: throw IllegalArgumentException("--probe-guarded-spider requires an explicitly trusted SHA-256")
            val abi = GuardedSpiderAbi.parse(args.getOrNull(probeGuardedIndex + 3) ?: "armeabi-v7a")
            TrustedJvmSpiderArtifact.resolve(location).use { artifact ->
                val runtime = GuardedSpiderRuntime(artifact.path, trustedSha256)
                println("Guarded spider package: OK")
                println("- artifact SHA-256: ${runtime.packageReport.sha256}")
                println("- ABIs: ${runtime.packageReport.supportedAbis.joinToString { it.configName }}")
                val result = runtime.decrypt(abi)
                println("Guarded spider ARM host: OK")
                println("- selected ABI: ${result.abi.configName}")
                println("- native module: ${result.module}")
                println("- loader type: ${result.loaderType ?: "none"}")
                println("- emulated files: ${result.outputFiles.joinToString().ifEmpty { "none" }}")
                println("- guard output: ${result.outputBytes} bytes, header ${result.outputHeader ?: "none"}")
                println("- decrypted DEX: ${if (result.dexProduced) "produced" else "not produced"}")
            }
        }

        prepareGuardedIndex >= 0 -> {
            val location = args.getOrNull(prepareGuardedIndex + 1)
                ?: throw IllegalArgumentException("--prepare-guarded-spider requires an artifact URL or file")
            val trustedSha256 = args.getOrNull(prepareGuardedIndex + 2)
                ?: throw IllegalArgumentException("--prepare-guarded-spider requires an explicitly trusted SHA-256")
            val abi = GuardedSpiderAbi.parse(args.getOrNull(prepareGuardedIndex + 3) ?: "arm64-v8a")
            TrustedJvmSpiderArtifact.resolve(location).use { artifact ->
                val runtime = GuardedSpiderRuntime(artifact.path, trustedSha256)
                val prepared = GuardedSpiderPreparer(
                    runtime.packageReport,
                    runtime,
                    GuardedRuntimeAssetsProducer(runtime::produceRuntimeAssets),
                ).prepare(abi)
                println("Guarded spider preparation: OK")
                println("- package SHA-256: ${prepared.packageSha256}")
                println("- selected ABI: ${prepared.abi.configName}")
                println("- prepared JAR: ${prepared.path}")
                println("- prepared SHA-256: ${prepared.artifactSha256}")
                println("- classes: ${prepared.classCount}")
                println("- cache: ${if (prepared.reused) "reused" else "created"}")
            }
        }

        guardedRunIndex >= 0 -> {
            val diagnostic = diagnoseGuardedIndex >= 0
            val commandName = if (diagnostic) "--diagnose-guarded-spider" else "--test-guarded-spider"
            val location = args.getOrNull(guardedRunIndex + 1)
                ?: throw IllegalArgumentException("$commandName requires a configuration URL")
            val query = args.getOrNull(guardedRunIndex + 2)
                ?: throw IllegalArgumentException("$commandName requires a query")
            val sourceKey = args.getOrNull(guardedRunIndex + 3)
                ?: throw IllegalArgumentException("$commandName requires a source key")
            val trustedSha256 = args.getOrNull(guardedRunIndex + 4)
                ?: throw IllegalArgumentException("$commandName requires an explicitly trusted SHA-256")
            val abi = GuardedSpiderAbi.parse(args.getOrNull(guardedRunIndex + 5) ?: "armeabi-v7a")
            val configuredSite = DesktopTvBoxConfigurationLoader().load(location).jvmSpiderSites
                .firstOrNull { it.key == sourceKey }
                ?: throw IllegalArgumentException("Configuration has no guarded CatVod source named $sourceKey")
            val site = configuredSite.jvmExecution!!
            val artifactLocation = args.getOrNull(guardedRunIndex + 6) ?: site.artifactLocation
            TrustedJvmSpiderArtifact.resolve(artifactLocation).use { artifact ->
                val runtime = GuardedSpiderRuntime(artifact.path, trustedSha256)
                val prepared = GuardedSpiderPreparer(
                    runtime.packageReport,
                    runtime,
                    GuardedRuntimeAssetsProducer(runtime::produceRuntimeAssets),
                ).prepare(abi)
                val guardedSite = GuardedCatVodSiteSpec(
                    site.key,
                    site.name,
                    site.api,
                    site.extension,
                    site.categories,
                )
                val executable = if (diagnostic) {
                    GuardedSpiderDiagnosticInstrumenter().instrument(prepared, guardedSite.className)
                } else prepared
                GuardedCatVodCatalogSourceExecutor(guardedSite, executable).use executorUse@{ executor ->
                    val home = executor.browseHome()
                    val featured = home.items
                    val category = home.types.firstOrNull()?.let { type ->
                        runCatching {
                            executor.category(type.id, 1, CatalogFilterSelectionPolicy.defaults(type))
                        }.getOrNull()
                    }
                    val items = if (configuredSite.searchable) executor.search(query) else emptyList()
                    println("Guarded CatVod source: OK")
                    println("- source: ${site.name}")
                    println("- provider class: ${guardedSite.className}")
                    println("- selected ABI: ${prepared.abi.configName}")
                    println("- preparation cache: ${if (prepared.reused) "reused" else "created"}")
                    if (diagnostic) println("- diagnostic cache: ${if (executable.reused) "reused" else "created"}")
                    println("- home VOD: ${featured.size}")
                    println("- media types: ${home.types.size}")
                    printFilterSummary(home)
                    category?.let { println("- first category VOD: ${it.items.size} · page ${it.page}/${it.pageCount.takeIf { count -> count > 0 } ?: "?"}") }
                    println("- search: ${if (configuredSite.searchable) "${items.size} results" else "skipped (index provider)"}")
                    require(featured.isNotEmpty() || category?.items?.isNotEmpty() == true || items.isNotEmpty()) {
                        "Guarded CatVod Home, first category and Search returned no VOD items"
                    }
                    require(home.types.isNotEmpty()) { "Guarded CatVod Home returned no media types" }
                    items.take(5).forEach { println("- ${it.name} · ${it.remarks} · ${it.id} · poster=${it.picture}") }
                    if (configuredSite.indexable) {
                        require(home.types.any { it.filters.isNotEmpty() }) {
                            "Guarded index provider returned no type filters"
                        }
                        println("- detail/playback: skipped (Android routes index cards to all-site title search)")
                        return@executorUse
                    }
                    val item = items.firstOrNull() ?: featured.first()
                    val detailProbe = if (items.isNotEmpty()) "search" else "home"
                    item.let {
                        val detail = executor.detail(item.id)
                        println("- detail probe: $detailProbe")
                        println("- playback lines: ${detail.lines.size}")
                        println("- episodes: ${detail.lines.sumOf { it.episodes.size }}")
                        require(detail.lines.isNotEmpty()) { "Guarded CatVod detail returned no playback lines" }
                        val candidates = buildList {
                            detail.lines.forEach { line ->
                                line.episodes.firstOrNull()?.let { add(line to it) }
                            }
                            detail.lines.firstOrNull()?.let { line ->
                                line.episodes.getOrNull(line.episodes.size / 2)?.let { add(line to it) }
                                line.episodes.lastOrNull()?.let { add(line to it) }
                            }
                        }.distinctBy { (line, episode) -> "${line.flag}\u0000${episode.id}" }
                        require(candidates.isNotEmpty()) { "Guarded CatVod detail returned no episodes" }
                        candidates.forEach { (line, candidate) ->
                            val playback = executor.play(line.flag, candidate.id)
                            val accountRequired = CloudDriveDetector.isAccountRequiredMessage(playback.message)
                            println("- playback ${line.name}/${candidate.name}: ${when {
                                playback.supported -> "supported"
                                accountRequired -> "skipped (${playback.message})"
                                else -> playback.message
                            }}")
                            println("- playback headers: ${playback.headers.size}")
                            playback.headers.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (name, value) ->
                                println("- playback header $name: ${playbackHeaderSummary(name, value)}")
                            }
                            require(playback.supported || accountRequired) {
                                "Guarded CatVod playback failed for ${line.name}/${candidate.name}: ${playback.message}"
                            }
                            if (playback.supported && probeHlsTransport(playback.url, playback.headers)) {
                                println(
                                    "- playback HLS transport ${candidate.name}: " +
                                        "manifest and first media resource reachable",
                                )
                            }
                        }
                    }
                }
            }
        }

        analyzePluginsIndex >= 0 -> {
            val outputDir = args.getOrNull(analyzePluginsIndex + 1)?.takeUnless { it.startsWith("--") }
                ?: "plugin-analysis"
            val dir = java.nio.file.Path.of(outputDir).toAbsolutePath()
            java.nio.file.Files.createDirectories(dir)
            println("Step 1: Downloading all plugins from depot.json...")
            val manifest = PluginBatchDownloader.run(dir)
            println()
            println("Step 2: Analyzing downloaded plugins...")
            val report = PluginAnalyzer.run(dir)
            println()
            println("Step 3: Generating report...")
            PluginAnalysisReport.generate(report, dir)
            println()
            println("Done. All outputs are in: $dir")
        }

        GraphicsEnvironment.isHeadless() -> {
            System.err.println(
                "TVBox Desktop needs a graphical session and a full Java desktop runtime. " +
                    "Use --smoke on headless hosts.",
            )
        }

        else -> {
            val allowMultiple = "--allow-multiple-instances" in args ||
                !com.wuming.tv.desktop.config.DesktopApplicationSettings().preventMultipleInstances
            val instance = DesktopInstanceGuard.acquire(allowMultiple)
            if (instance == null) {
                System.err.println("無名 is already running. Open it using the tray icon.")
                SwingUtilities.invokeLater {
                    javax.swing.JOptionPane.showMessageDialog(null,
                        "無名 is already running. Open it using the tray icon.", "無名",
                        javax.swing.JOptionPane.INFORMATION_MESSAGE)
                }
                return
            }
            Runtime.getRuntime().addShutdownHook(Thread({ instance.close() }, "tvbox-instance-lock"))
            val systemDark = com.wuming.tv.desktop.ui.LinuxSystemAppearance().isDark()
            SwingUtilities.invokeLater {
                com.wuming.tv.desktop.config.DesktopAppearanceSettings().let {
                    DesktopTvTheme.install(it.colorTheme, it.appearance, systemDark)
                }
                DesktopWindow().show()
                DesktopTvTheme.monitorSystemAppearance()
            }
        }
    }
}

/** A supported provider response is not enough: exercise the same protected-HLS relay used by VLC. */
private fun probeHlsTransport(location: String, headers: Map<String, String>): Boolean {
    val upstream = runCatching { URI(location) }.getOrNull() ?: return false
    if (!upstream.path.orEmpty().endsWith(".m3u8", ignoreCase = true)) return false
    val prepared = HlsCompatibilityPreparer().prepare(location, headers)
    try {
        val loader = DesktopResourceLoader(maximumBytes = 32 * 1024 * 1024)
        var current = URI(prepared.location)
        repeat(5) {
            val bytes = loader.load(current.toASCIIString())
            val text = bytes.toString(StandardCharsets.UTF_8).trimStart('\uFEFF', ' ', '\r', '\n', '\t')
            if (!text.startsWith("#EXTM3U")) {
                require(bytes.isNotEmpty()) { "HLS media resource was empty" }
                return true
            }
            val reference = text.lineSequence().map(String::trim)
                .firstOrNull { it.isNotEmpty() && !it.startsWith('#') }
                ?: return true
            current = current.resolve(reference)
        }
        error("HLS manifest nesting exceeded the desktop smoke-test limit")
    } finally {
        prepared.resource?.close()
    }
}

private fun playbackHeaderSummary(name: String, value: String): String = when {
    name.equals("Origin", true) || name.equals("Referer", true) -> runCatching {
        val uri = URI(value)
        "${uri.scheme}://${uri.host}${uri.port.takeIf { it >= 0 }?.let { ":$it" }.orEmpty()}"
    }.getOrDefault("invalid URI (${value.length} characters)")
    name.equals("User-Agent", true) -> value.take(80)
    else -> "${value.length} characters"
}

private fun printFilterSummary(home: CatalogHome) {
    val filteredTypes = home.types.filter { it.filters.isNotEmpty() }
    println("- filter rows: ${filteredTypes.sumOf { it.filters.size }} across ${filteredTypes.size} types")
    home.types.forEach { type ->
        println("- type ${type.name}: id=${type.id} · folder=${type.folder} · filters=${type.filters.size}")
    }
    filteredTypes.take(5).forEach { type ->
        println("- filters ${type.name}: ${type.filters.joinToString { "${it.name} (${it.values.size})" }}")
    }
}
