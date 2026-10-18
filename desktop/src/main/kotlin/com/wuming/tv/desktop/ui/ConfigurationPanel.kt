package com.wuming.tv.desktop.ui

import com.wuming.tv.core.catalog.CatalogSourceExecutor
import com.wuming.tv.core.catalog.CatalogVerificationProbe
import com.wuming.tv.desktop.jvmspider.restoreTrustedSources
import com.wuming.tv.desktop.jvmspider.DesktopSpiderRuntime
import com.wuming.tv.desktop.jvmspider.DesktopSpiderVerificationStore
import javax.swing.SwingUtilities
import com.wuming.tv.core.runtime.guardspider.GuardedSpiderAbi
import com.wuming.tv.core.source.TvBoxSourceRuntime
import com.wuming.tv.core.source.config.TvBoxWarehouse
import com.wuming.tv.core.source.config.TvBoxWarehouseChild
import com.wuming.tv.desktop.config.DesktopTvBoxConfiguration
import com.wuming.tv.desktop.config.DesktopTvBoxConfigurationLoader
import com.wuming.tv.desktop.config.DesktopTvBoxSite
import com.wuming.tv.desktop.config.DesktopConfigurationUrlStore
import com.wuming.tv.desktop.config.DesktopConfigurationSiteStore
import com.wuming.tv.desktop.config.DesktopDepot
import com.wuming.tv.desktop.config.DesktopDepotSource
import com.wuming.tv.core.source.config.SourceBrowserEntry
import com.wuming.tv.core.source.config.SourceKind
import com.wuming.tv.core.source.config.SourceOrigin
import com.wuming.tv.core.source.config.OfflineLinkCatalog
import com.wuming.tv.core.source.config.ProtectedSourceUrl
import com.wuming.tv.desktop.config.DesktopDepotVodStore
import com.wuming.tv.desktop.config.DesktopStartupConfiguration
import com.wuming.tv.desktop.net.DesktopHttpStatusException
import com.wuming.tv.desktop.net.DesktopResourceLoader
import com.wuming.tv.desktop.net.ghproxyUrl
import java.io.IOException
import javax.swing.Timer
import com.wuming.tv.desktop.jvmspider.GuardedSourceActivator
import com.wuming.tv.desktop.jvmspider.GuardedConfigFingerprintStore
import com.wuming.tv.desktop.jvmspider.GuardedDesktopSupport
import com.wuming.tv.desktop.jvmspider.GuardedDesktopSupportPolicy
import com.wuming.tv.desktop.jvmspider.GuardedSourceTrust
import com.wuming.tv.desktop.jvmspider.SpiderArtifactChangedException
import com.wuming.tv.desktop.jvmspider.SpiderArtifactKind
import com.wuming.tv.desktop.jvmspider.GuardedSourceTrustStore
import com.wuming.tv.desktop.jvmspider.SpiderArtifactFingerprint
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.util.prefs.Preferences
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JOptionPane
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingWorker
import java.util.logging.Level
import java.util.logging.Logger

class ConfigurationPanel(
    private val loader: DesktopTvBoxConfigurationLoader = DesktopTvBoxConfigurationLoader(),
    private val preferences: Preferences = Preferences.userNodeForPackage(ConfigurationPanel::class.java),
    private val onLoaded: (DesktopTvBoxConfiguration) -> Unit = {},
    private val onGuardedEnabled: (List<CatalogSourceExecutor>) -> Unit = {},
    private val guardedActivator: GuardedSourceActivator = GuardedSourceActivator(),
    private val onStartupStatus: (String) -> Unit = {},
    private val startupRetryDelaysMillis: List<Int> = listOf(2_000, 5_000),
    private val depot: DesktopDepot = DesktopDepot.load(),
    private val onApprovalRequired: (() -> Unit) -> Unit = {},
    private val onSourcesRestored: (List<CatalogSourceExecutor>) -> Unit = onGuardedEnabled,
    private val onSpiderEnabled: (CatalogSourceExecutor) -> Unit = { onGuardedEnabled(listOf(it)) },
) : JPanel(BorderLayout(8, 10)) {
    private val logger = Logger.getLogger("tvbox.ui.configuration")
    private val repeatRequests = RepeatRequestGate()
    private val urlStore = DesktopConfigurationUrlStore(preferences)
    private val siteStore = DesktopConfigurationSiteStore(preferences)
    private val trustStore = GuardedSourceTrustStore(preferences)
    private val evidenceStore = DesktopSpiderVerificationStore(preferences)
    private val spiderRuntime = DesktopSpiderRuntime(evidenceStore, guardedActivator) {
        SwingUtilities.invokeLater { refreshSiteRows() }
    }
    private val activeSpiderKeys = mutableSetOf<String>()
    private val updatedSpiderKeys = mutableSetOf<String>()
    private val reviewUpdated = JButton("Review updated bundle…").apply { isVisible = false }
    private var checkingSpider = false
    private val configFingerprintStore = GuardedConfigFingerprintStore(preferences)
    private val depotVodStore = DesktopDepotVodStore(preferences)
    private var updatingDepot = false
    private fun packageSources(): List<DesktopDepotSource> = runCatching {
        com.wuming.tv.desktop.packages.DesktopSourcePackages.library.installed().map {
            DesktopDepotSource("${it.name} (local)", "package://${it.id}")
        }
    }.onFailure { logger.log(Level.WARNING, "Unable to read installed source packages", it) }.getOrDefault(emptyList())

    private val location = JTextField(DesktopStartupConfiguration.location(
        depot, urlStore.lastWorkingOr(""), depotVodStore.rememberedLocation(),
    ))
    private val bundledLocation = DesktopBundledLocation(location)
    private val depotVod = JComboBox((depot.vod + packageSources()).toTypedArray())
    private val load = JButton("Load locally")
    private val forceReload = JButton("Force reload")
    private val enableGuarded = JButton("Review source trust…").apply { isEnabled = false }
    private val checkSpider = JButton("Check source…").apply { isEnabled = false }
    private val summary = JTextArea()
    private val sites = JList(DefaultListModel<String>())
    private val lives = JList(DefaultListModel<String>())
    private val status = JLabel("Enter a TVBox configuration URL. Android is not used.")
    private var configuration: DesktopTvBoxConfiguration? = null
    private var updatingSites = false
    private var startupRequested = false
    private var loadGeneration = 0
    private var retryTimer: Timer? = null
    private var loadWorker: SwingWorker<DesktopTvBoxConfiguration, Void>? = null
    private var warehouseWorker: SwingWorker<List<TvBoxWarehouseChild>, Void>? = null

    init {
        lockBundledStartupSource()
        border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        summary.isEditable = false
        summary.lineWrap = true
        summary.wrapStyleWord = true
        add(JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            add(JPanel(BorderLayout(8, 0)).apply {
                add(JLabel("TVBox configuration URL"), BorderLayout.WEST)
                add(this@ConfigurationPanel.location, BorderLayout.CENTER)
                add(load, BorderLayout.EAST)
            })
            add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 6)).apply {
                add(forceReload)
                add(JButton("Browse warehouse…").apply {
                    addActionListener { browseWarehouse(bundledLocation.value()) }
                })
                add(JButton("Browse sources…").apply {
                    addActionListener {
                        val curated = (depot.vod + packageSources()).map { source ->
                            SourceBrowserEntry(source.name, source.location, SourceKind.VOD,
                                if (source.location.startsWith("package://")) SourceOrigin.LOCAL else SourceOrigin.CURATED,
                                source.warehouse)
                        }
                        runCatching { DesktopOfflineLinkPicker.choose(this@ConfigurationPanel, SourceKind.VOD, curated) }
                            .onSuccess { selected ->
                                if (selected != null) {
                                    if (selected.origin == SourceOrigin.LOCAL) bundledLocation.manual(selected.url)
                                    else bundledLocation.select(selected)
                                    if (selected.warehouse) browseWarehouse(selected.url)
                                    else status.text = "${selected.name} selected. Choose Load locally to try it."
                                }
                            }.onFailure { status.text = rootMessage(it) }
                    }
                })
                add(JButton("Edit source URL…").apply {
                    addActionListener { bundledLocation.unlock(this@ConfigurationPanel) }
                })
                add(JButton("Import package…").apply {
                    addActionListener {
                        com.wuming.tv.desktop.packages.PackageImportDialog.show(this@ConfigurationPanel) { item ->
                            refreshPackageSources()
                            bundledLocation.manual("package://${item.id}")
                            status.text = "${item.name} installed. Choose Load locally to activate it."
                        }
                    }
                })
                add(JButton("Roll back package").apply {
                    addActionListener { rollbackPackage() }
                })
                add(checkSpider)
                add(enableGuarded)
            })
            add(JPanel(BorderLayout(8, 0)).apply {
                border = BorderFactory.createEmptyBorder(6, 0, 0, 0)
                add(JLabel("Built-in VOD source"), BorderLayout.WEST)
                add(depotVod, BorderLayout.CENTER)
                add(JButton("Clear resource caches").apply {
                    toolTipText = "Clear downloaded data and catalogue caches; keep subscriptions, favorites, accounts and provider trust"
                    addActionListener {
                        isEnabled = false
                        val button = this
                        object : SwingWorker<Unit, Void>() {
                            override fun doInBackground() = com.wuming.tv.desktop.cache.DesktopCaches.clear()
                            override fun done() {
                                button.isEnabled = true
                                status.text = runCatching { get(); "Resource caches cleared." }
                                    .getOrElse { "Could not clear all resource caches: ${it.cause?.message ?: it.message}" }
                            }
                        }.execute()
                    }
                }, BorderLayout.EAST)
            })
        }, BorderLayout.NORTH)
        add(JSplitPane(JSplitPane.VERTICAL_SPLIT, JScrollPane(summary), lists()).apply {
            resizeWeight = 0.25
            dividerLocation = 145
        }, BorderLayout.CENTER)
        add(JPanel(BorderLayout(8, 0)).apply {
            add(status, BorderLayout.CENTER)
            add(reviewUpdated, BorderLayout.EAST)
        }, BorderLayout.SOUTH)
        load.addActionListener { requestConfiguration("user") }
        forceReload.addActionListener { requestConfiguration("force", forceRefresh = true) }
        location.addActionListener { requestConfiguration("user") }
        restoreDepotVodSelection()
        depotVod.addActionListener {
            if (updatingDepot) return@addActionListener
            (depotVod.selectedItem as? DesktopDepotSource)?.let { source ->
                if (source.location.startsWith("package://")) bundledLocation.manual(source.location)
                else bundledLocation.select(source.location, source.name)
                if (source.warehouse) browseWarehouse(source.location)
                else {
                    depotVodStore.rememberLocation(source.location)
                    requestConfiguration("depot")
                }
            }
        }
        sites.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                if (!updatingSites) rememberSelectedSite()
                updateGuardedAction()
            }
        }
        reviewUpdated.addActionListener {
            configuration?.sites?.firstOrNull { it.key in updatedSpiderKeys }?.let {
                inspectGuardedSource(updatedSite = it)
            }
        }
        enableGuarded.addActionListener { inspectGuardedSource() }
        checkSpider.addActionListener { checkSelectedSpider() }
    }

    private fun lockBundledStartupSource() {
        val current = bundledLocation.value()
        depot.vod.firstOrNull { it.location == current }?.let {
            bundledLocation.select(it.location, it.name)
            return
        }
        val screened = runCatching {
            javaClass.getResourceAsStream("/link_catalog.json")?.use { OfflineLinkCatalog.parse(it.readBytes()) }
                .orEmpty().flatMap { it.entries }
        }.getOrDefault(emptyList())
        screened.firstOrNull { it.url == current }?.let { bundledLocation.select(it.url, it.name) }
    }

    private fun refreshPackageSources() {
        updatingDepot = true
        try {
            depotVod.removeAllItems()
            (depot.vod + packageSources()).forEach(depotVod::addItem)
            restoreDepotVodSelection()
        } finally { updatingDepot = false }
    }

    private fun rollbackPackage() {
        val selected = bundledLocation.value()
        if (!selected.startsWith("package://")) { status.text = "Select an installed local package first."; return }
        object : SwingWorker<Unit, Void>() {
            override fun doInBackground() {
                com.wuming.tv.desktop.packages.DesktopSourcePackages.library.rollback(selected.removePrefix("package://"))
            }
            override fun done() {
                runCatching { get() }.onSuccess {
                    status.text = "Previous package restored. Choose Load locally to activate it."
                }.onFailure { status.text = rootMessage(it) }
            }
        }.execute()
    }

    private fun requestConfiguration(reason: String, forceRefresh: Boolean = false) {
        val source = bundledLocation.value()
        if (repeatRequests.accept("configuration:$forceRefresh:$source")) {
            loadConfiguration(reason, forceRefresh)
        }
    }

    private fun browseWarehouse(value: String) {
        if (value.isBlank()) { status.text = "Enter a warehouse URL first."; return }
        warehouseWorker?.cancel(true)
        status.text = "Loading warehouse choices…"
        warehouseWorker = object : SwingWorker<List<TvBoxWarehouseChild>, Void>() {
            override fun doInBackground(): List<TvBoxWarehouseChild> {
                val input = ghproxyUrl(value)
                val bytes = DesktopResourceLoader().load(input)
                return TvBoxWarehouse.parseOrNull(bytes, input)
                    ?: throw IllegalArgumentException("This URL is not a warehouse index.")
            }

            override fun done() {
                if (isCancelled) return
                runCatching { get() }.onSuccess { children ->
                    if (children.isEmpty()) { status.text = "This warehouse has no usable configurations."; return@onSuccess }
                    val selected = JOptionPane.showInputDialog(
                        this@ConfigurationPanel, "Choose a configuration", "Warehouse",
                        JOptionPane.QUESTION_MESSAGE, null, children.toTypedArray(), children.first(),
                    ) as? TvBoxWarehouseChild ?: run { status.text = "Warehouse choice cancelled."; return@onSuccess }
                    bundledLocation.select(selected.url, selected.name)
                    requestConfiguration("warehouse")
                }.onFailure { status.text = rootMessage(it) }
            }
        }.also { it.execute() }
    }

    private fun lists(): JSplitPane = JSplitPane(
        JSplitPane.HORIZONTAL_SPLIT,
        JPanel(BorderLayout(6, 6)).apply {
            add(JLabel("Sites and required runtimes"), BorderLayout.NORTH)
            add(JScrollPane(sites), BorderLayout.CENTER)
        },
        JPanel(BorderLayout(6, 6)).apply {
            add(JLabel("Live playlists"), BorderLayout.NORTH)
            add(JScrollPane(lives), BorderLayout.CENTER)
        },
    ).apply {
        resizeWeight = 0.65
        dividerLocation = 560
    }

    fun loadSavedConfiguration() {
        if (startupRequested) return
        startupRequested = true
        loadConfiguration("startup")
    }

    fun cancelLoading() {
        loadGeneration++
        retryTimer?.stop()
        loadWorker?.cancel(true)
        warehouseWorker?.cancel(true)
    }

    private fun loadConfiguration(trigger: String, forceRefresh: Boolean = false, attempt: Int = 0) {
        retryTimer?.stop()
        loadWorker?.cancel(true)
        val generation = ++loadGeneration
        val value = bundledLocation.value()
        if (value.isEmpty()) { status.text = "Enter a configuration URL."; return }
        load.isEnabled = false
        forceReload.isEnabled = false
        status.text = if (forceRefresh) "Force-refreshing and decoding locally…" else "Loading configuration…"
        if (trigger == "startup") onStartupStatus("Loading your site configuration automatically…")
        logger.info("load requested trigger=$trigger forceRefresh=$forceRefresh attempt=${attempt + 1}")
        loadWorker = object : SwingWorker<DesktopTvBoxConfiguration, Void>() {
            override fun doInBackground(): DesktopTvBoxConfiguration = loader.load(value, forceRefresh)

            override fun done() {
                if (generation != loadGeneration) return
                load.isEnabled = true
                forceReload.isEnabled = true
                runCatching { get() }.onSuccess { show(it, trigger == "startup") }.onFailure { failure ->
                    var cause = failure
                    while (cause.cause != null) cause = cause.cause!!
                    val retryable = cause is IOException ||
                        (cause is DesktopHttpStatusException && (cause.statusCode in 500..599 || cause.statusCode in setOf(408, 429)))
                    if (ProtectedSourceUrl.hasCredential(bundledLocation.value()))
                        logger.warning("load failed for protected source: ${rootMessage(failure)}")
                    else logger.log(Level.WARNING, "load failed error=${rootMessage(failure)}", failure)
                    val delay = startupRetryDelaysMillis.getOrNull(attempt)
                    if (trigger == "startup" && retryable && delay != null) {
                        status.text = "Site configuration unavailable; retrying automatically…"
                        onStartupStatus(status.text)
                        retryTimer = Timer(delay) {
                            if (generation == loadGeneration) loadConfiguration("startup", attempt = attempt + 1)
                        }.apply { isRepeats = false; start() }
                    } else {
                        status.text = rootMessage(failure)
                        if (trigger == "startup") onStartupStatus("Could not load the site configuration. Check your connection or retry in Settings.")
                    }
                }
            }
        }.also { it.execute() }
    }

    private fun show(configuration: DesktopTvBoxConfiguration, startup: Boolean = false) {
        logger.info("load shown cache=${configuration.reusedCache} sites=${configuration.sites.size}")
        // onLoaded rebuilds the catalogue and retires its executors, even for identical JSON.
        activeSpiderKeys.clear()
        updatedSpiderKeys.clear()
        reviewUpdated.isVisible = false
        this.configuration = configuration
        val guardedCandidates = configuration.sites.withIndex().filter { isGuardedCandidate(it.value) }
        val verifiedProviders = guardedCandidates.filter { support(it.value).activationAllowed }
        urlStore.rememberWorking(configuration.location)
        logger.info("last working configuration persisted")
        summary.text = buildString {
            append(when (configuration.cacheStatus) {
                "stale" -> "Offline — using stale configuration: "
                "fresh" -> "Loaded fresh cache: "
                "revalidated" -> "Configuration revalidated: "
                else -> "Loaded locally: "
            })
            append("${configuration.sites.size} sites, ")
            append("${configuration.searchableSites.size} searchable, ${configuration.browsableSites.size} browse-capable, ")
            append("${configuration.lives.size} live playlists.\n")
            append("Direct JavaScript candidates: ${configuration.directJavaScriptSites.size}. ")
            append("Spider-bound browse sources: ${configuration.browsableSites.count {
                it.runtime == TvBoxSourceRuntime.SPIDER_RUNTIME_REQUIRED
            }}. Verified guarded desktop sources: ${verifiedProviders.size}; ")
            append("other guarded sources: ${guardedCandidates.size - verifiedProviders.size}.\n\n")
            append("HLS ad-filter rules: ${configuration.hlsAdRules.size}; applied to VOD and Live playback.\n\n")
            if (configuration.resolvedLocation != configuration.location) {
                append("The requested page advertised a fallback configuration endpoint, which was decoded locally.\n\n")
            }
            append("Direct JavaScript sources are now available in the Catalogue tab through the local JVM runtime. ")
            append("Android is not consulted.")
        }
        updatingSites = true
        model(sites).apply {
            clear()
            configuration.sites.forEach { site ->
                addElement(siteRow(site))
            }
        }
        model(lives).apply {
            clear()
            configuration.lives.forEach { addElement(it.name) }
        }
        val rememberedKey = siteStore.rememberedSiteKey()
        sites.selectedIndex = configuration.sites.indexOfFirst { it.key == rememberedKey }
            .takeIf { it >= 0 }
            ?: verifiedProviders.firstOrNull()?.index
            ?: configuration.sites.indices.firstOrNull()
            ?: -1
        updatingSites = false
        onLoaded(configuration)
        updateGuardedAction()
        val cacheNotice = if (configuration.reusedCache) "Using validated local configuration cache. " else ""
        val trusted = guardedCandidates.mapNotNull { indexed ->
            indexed.value.jvmExecution?.let(trustStore::find)?.let { indexed.value to it }
        }
        logger.info("provider approvals verified=${verifiedProviders.size} remembered=${trusted.size} startup=$startup")
        // Existing approvals must not short-circuit newly eligible default providers:
        // browse-only sites would otherwise remain inactive after an upgrade.
        if (startup && DesktopStartupConfiguration.automaticallyApprove(depot, configuration.location) &&
            verifiedProviders.any { it.value.jvmExecution?.let(trustStore::find) == null }) {
            inspectGuardedSource(approveDefault = true)
        } else if (trusted.isNotEmpty()) {
            activateTrusted(trusted, cacheNotice)
        } else {
            status.text = verifiedProviders.firstOrNull()?.value?.let {
                "${cacheNotice}${verifiedProviders.size} guarded sources are verified; inspect and approve their exact artifact once."
            } ?: "Configuration loaded, but it contains no verified guarded desktop source."
            if (startup && verifiedProviders.isNotEmpty()) {
                if (DesktopStartupConfiguration.automaticallyApprove(depot, configuration.location)) {
                    logger.info("startup automatically enabling built-in default providers")
                    inspectGuardedSource(approveDefault = true)
                } else {
                    logger.info("startup requires provider approval; opening setup")
                    onApprovalRequired {
                        if (this.configuration === configuration) inspectGuardedSource()
                    }
                }
            }
        }
    }

    private fun selectedGuardedSite(): DesktopTvBoxSite? = configuration?.sites
        ?.getOrNull(sites.selectedIndex)
        ?.takeIf { isGuardedCandidate(it) && support(it).activationAllowed }

    private fun verifiedGuardedSites(): List<DesktopTvBoxSite> = configuration?.sites.orEmpty()
        .filter { isGuardedCandidate(it) && support(it).activationAllowed }

    private fun rememberSelectedSite() {
        val site = configuration?.sites?.getOrNull(sites.selectedIndex) ?: return
        siteStore.rememberSiteKey(site.key)
        logger.info("settings site selection remembered key=${site.key}")
    }

    private fun restoreDepotVodSelection() {
        // A custom saved URL intentionally leaves the built-in selector empty.
        depotVod.selectedIndex = (0 until depotVod.itemCount).firstOrNull { depotVod.getItemAt(it).location == bundledLocation.value() } ?: -1
        logger.info("depot VOD selection restored index=${depotVod.selectedIndex} name=${depotVod.selectedItem}")
    }

    // Runtime capability is independent of whether this configuration exposes a catalogue source.
    private fun hasSpiderRuntime(site: DesktopTvBoxSite): Boolean = site.jvmExecution != null

    private fun isGuardedCandidate(site: DesktopTvBoxSite): Boolean =
        site.browseEnabled && hasSpiderRuntime(site)

    private fun support(site: DesktopTvBoxSite): GuardedDesktopSupport {
        val spec = site.jvmExecution ?: return GuardedDesktopSupport.UNVERIFIED
        val measured = trustStore.find(spec)?.let {
            evidenceStore.support(DesktopSpiderVerificationStore.identity(spec, it), site.indexable)
        }
        return measured ?: GuardedDesktopSupportPolicy.classify(spec)
    }

    private fun siteRow(site: DesktopTvBoxSite): String {
        val visibility = when {
            site.inputOnly -> "input handler · no catalogue"
            !site.browseEnabled -> "hidden by configuration"
            site.searchable && site.indexable -> "searchable · index provider"
            site.indexable -> "index provider · not searchable"
            site.searchable -> "searchable"
            else -> "browse-only · not searchable"
        }
        val runtime = when {
            site.key in activeSpiderKeys -> "desktop spider runtime active"
            site.key in updatedSpiderKeys -> "bundle updated · review approval required"
            hasSpiderRuntime(site) -> "desktop spider adapter available"
            site.runtime == TvBoxSourceRuntime.SPIDER_RUNTIME_REQUIRED -> "no supported desktop spider adapter"
            else -> site.runtime.label()
        }
        val verification = if (hasSpiderRuntime(site)) " · ${support(site).label}" else ""
        return "${site.name} · $visibility · $runtime$verification"
    }

    private fun refreshSiteRows() {
        configuration?.sites?.forEachIndexed { index, site ->
            if (index < model(sites).size()) model(sites).set(index, siteRow(site))
        }
    }

    private fun updateGuardedAction() {
        enableGuarded.isEnabled = load.isEnabled && verifiedGuardedSites().isNotEmpty()
        checkSpider.isEnabled = load.isEnabled && !checkingSpider && configuration?.sites
            ?.getOrNull(sites.selectedIndex)?.let(::hasSpiderRuntime) == true
        val selected = configuration?.sites?.getOrNull(sites.selectedIndex) ?: return
        if (!hasSpiderRuntime(selected)) {
            status.text = if (selected.runtime == TvBoxSourceRuntime.SPIDER_RUNTIME_REQUIRED) {
                "${selected.name} has no supported JVM spider specification. Check its artifact and API format."
            } else "${selected.name}: ${selected.runtime.label()}."
            return
        }
        when (support(selected)) {
            GuardedDesktopSupport.VERIFIED ->
                status.text = "${selected.name} has a verified desktop runtime; playback availability depends on the source and selected line."
            GuardedDesktopSupport.VERIFIED_CATALOG ->
                status.text = "${selected.name} is verified for desktop catalogue and episode details; playback is not verified."
            GuardedDesktopSupport.VERIFIED_PLAYBACK_URL ->
                status.text = "${selected.name} returned catalogue data and a playback URL on desktop; media rendering is not verified."
            GuardedDesktopSupport.VERIFIED_INDEX ->
                status.text = "${selected.name} is verified for desktop discovery; its cards search all enabled sites."
            GuardedDesktopSupport.CLOUD_SEARCH ->
                status.text = "${selected.name} is verified for desktop cloud search and detail; playback may require Accounts login."
            GuardedDesktopSupport.UNVERIFIED ->
                status.text = "${selected.name} has no catalogue verification yet. Use Check selected spider to test its runtime."
        }
        if (!selected.browseEnabled) {
            status.text += " This is a hidden site or input-only handler, not a VOD catalogue."
        }
    }

    /** Unknown providers can now earn support without a source-code allow-list edit. */
    private fun checkSelectedSpider() {
        val current = configuration ?: return
        val selected = current.sites.getOrNull(sites.selectedIndex)?.takeIf(::hasSpiderRuntime) ?: return
        val spec = selected.jvmExecution ?: return
        val generation = loadGeneration
        checkingSpider = true
        setBusy(true, "Inspecting ${selected.name} before checking its desktop runtime…")
        object : SwingWorker<SpiderArtifactFingerprint, Void>() {
            override fun doInBackground() = spiderRuntime.inspect(spec)
            override fun done() {
                if (generation != loadGeneration) return
                val fingerprint = runCatching { get() }.getOrElse {
                    checkingSpider = false
                    setBusy(false, rootMessage(it))
                    return
                }
                if (fingerprint.kind == SpiderArtifactKind.UNKNOWN) {
                    checkingSpider = false
                    setBusy(false, "Unsupported spider package: a portable JVM JAR or supported DEX bundle is required.")
                    return
                }
                val previousTrust = trustStore.find(spec)
                val abi = previousTrust?.abi?.takeIf { fingerprint.supportedAbis.isEmpty() || it.configName in fingerprint.supportedAbis }
                    ?: GuardedSpiderAbi.parse(fingerprint.supportedAbis.firstOrNull { it == "armeabi-v7a" }
                        ?: fingerprint.supportedAbis.firstOrNull() ?: "armeabi-v7a")
                val trust = GuardedSourceTrust(fingerprint.sha256, abi)
                if (previousTrust != trust) {
                    val approved = JOptionPane.showConfirmDialog(
                        this@ConfigurationPanel,
                        "Check ${selected.name} in an isolated desktop JVM?\n" +
                            "SHA-256: ${fingerprint.sha256}\n" +
                            "This executes remote provider code and requests catalogue/search/detail data.\n" +
                            "Playback and cloud-save operations are not requested by the check.",
                        "Trust and check selected spider?", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE,
                    )
                    if (approved != JOptionPane.OK_OPTION) {
                        checkingSpider = false
                        setBusy(false, "Spider check cancelled.")
                        return
                    }
                }
                val query = if (selected.searchable) JOptionPane.showInputDialog(
                    this@ConfigurationPanel, "Search term for the check (blank skips search):", "仙逆",
                ) else ""
                if (query == null) {
                    checkingSpider = false
                    setBusy(false, "Spider check cancelled.")
                    return
                }
                setBusy(true, "Checking ${selected.name}: home, categories, search and episode details…")
                object : SwingWorker<CatalogSourceExecutor, Void>() {
                    override fun doInBackground(): CatalogSourceExecutor {
                        val executor = spiderRuntime.activate(spec, trust)
                        try {
                            val checked = CatalogVerificationProbe.check(executor, selected.searchable, selected.indexable, query)
                            val now = System.currentTimeMillis()
                            check(if (selected.indexable) checked.discoveryVerified(now) else checked.catalogVerified(now)) {
                                "Check incomplete: no usable catalogue and episode evidence (or discovery categories). " +
                                    "The provider returned empty data or a request failed; try another search term or retry later."
                            }
                            check(evidenceStore.support(DesktopSpiderVerificationStore.identity(spec, trust), selected.indexable) != null) {
                                "Catalogue check passed, but verification could not be saved."
                            }
                            return executor
                        } catch (failure: Throwable) {
                            runCatching { (executor as? AutoCloseable)?.close() }
                            throw failure
                        }
                    }
                    override fun done() {
                        val result = runCatching { get() }
                        if (generation != loadGeneration) {
                            result.getOrNull()?.let { runCatching { (it as? AutoCloseable)?.close() } }
                            return
                        }
                        checkingSpider = false
                        result.onSuccess { executor ->
                            runCatching {
                                trustStore.remember(spec, trust.sha256, trust.abi)
                                if (isGuardedCandidate(selected)) onSpiderEnabled(executor)
                                else (executor as? AutoCloseable)?.close()
                            }.onSuccess {
                                if (isGuardedCandidate(selected)) activeSpiderKeys.add(spec.key)
                                refreshSiteRows()
                                val outcome = if (isGuardedCandidate(selected)) "source enabled" else "catalogue visibility remains disabled by configuration"
                                setBusy(false, "${selected.name}: ${support(selected).label}. Verification saved; $outcome.")
                            }.onFailure {
                                runCatching { (executor as? AutoCloseable)?.close() }
                                setBusy(false, rootMessage(it))
                            }
                        }.onFailure { setBusy(false, rootMessage(it)) }
                    }
                }.execute()
            }
        }.execute()
    }

    private fun inspectGuardedSource(approveDefault: Boolean = false, updatedSite: DesktopTvBoxSite? = null) {
        val inspectedConfiguration = configuration ?: return
        val selected = updatedSite ?: selectedGuardedSite() ?: verifiedGuardedSites().firstOrNull() ?: return
        val spec = selected.jvmExecution ?: return
        setBusy(true, "Downloading and fingerprinting ${selected.name} without executing it…")
        object : SwingWorker<SpiderArtifactFingerprint, Void>() {
            override fun doInBackground(): SpiderArtifactFingerprint = spiderRuntime.inspect(spec)

            override fun done() {
                if (configuration !== inspectedConfiguration) return
                runCatching { get() }.onSuccess { fingerprint ->
                    setBusy(false, "Guarded artifact inspected; no code has been executed.")
                    confirmAndActivate(selected, fingerprint, approveDefault, reviewUpdate = updatedSite != null)
                }.onFailure {
                    setBusy(false, rootMessage(it))
                }
            }
        }.execute()
    }

    private fun confirmAndActivate(site: DesktopTvBoxSite, fingerprint: SpiderArtifactFingerprint, approveDefault: Boolean = false, reviewUpdate: Boolean = false) {
        val spec = site.jvmExecution ?: return
        if (configuration?.sites?.any { it.key == site.key && it.jvmExecution == spec } != true) return
        // Expanding the default batch must retain prior approvals for this exact artifact;
        // rebuilding it from the verification list alone can drop working custom providers.
        val previouslyApproved = if (approveDefault) configuration?.sites.orEmpty().filter { candidate ->
            isGuardedCandidate(candidate) && candidate.jvmExecution?.let(trustStore::find)?.sha256 == fingerprint.sha256
        } else emptyList()
        val batch = if (reviewUpdate) {
            configuration?.sites.orEmpty().filter {
                it.key in updatedSpiderKeys && isGuardedCandidate(it) &&
                    it.jvmExecution?.artifactLocation == spec.artifactLocation
            }
        } else (verifiedGuardedSites() + previouslyApproved).distinctBy { it.key }
            .filter { it.jvmExecution?.artifactLocation == spec.artifactLocation }
        if (batch.isEmpty()) return
        val abiNames = (if (fingerprint.kind == SpiderArtifactKind.PORTABLE_CLASS_JAR) {
            listOf(GuardedSpiderAbi.ARMEABI_V7A.configName)
        } else fingerprint.supportedAbis).toTypedArray()
        if (abiNames.isEmpty()) { status.text = "Guarded package contains no supported ABI."; return }
        val preferredAbi = abiNames.firstOrNull { it == GuardedSpiderAbi.ARMEABI_V7A.configName } ?: abiNames.first()
        if (approveDefault && DesktopStartupConfiguration.automaticallyApprove(depot, configuration!!.location)) {
            logger.info("built-in default provider approval sources=${batch.size} sha256=${fingerprint.sha256}")
            activate(batch, fingerprint.sha256, GuardedSpiderAbi.parse(preferredAbi), remember = true)
            return
        }
        val abi = JComboBox(abiNames).apply {
            selectedItem = preferredAbi
        }
        val warning = JTextArea(buildString {
            appendLine(if (reviewUpdate) "Previously approved sources in this updated bundle: ${batch.size}"
                else "Verified sources in this bundle: ${batch.size}")
            if (reviewUpdate) {
                appendLine("The bundle has changed since these sources were approved.")
                appendLine("Previous SHA-256:")
                batch.mapNotNull { it.jvmExecution?.let(trustStore::find)?.sha256 }.distinct().forEach { appendLine(it) }
            }
            batch.forEach { appendLine("• ${it.name}") }
            appendLine("SHA-256: ${fingerprint.sha256}")
            appendLine("Size: ${fingerprint.bytes} bytes")
            appendLine()
            appendLine("This untrusted remote bundle will run each provider in a memory-limited child JVM.")
            appendLine("Approval is remembered for only these providers and this exact SHA-256.")
            appendLine("Verification is capability-specific; catalogue success does not guarantee playback.")
            appendLine("Discovery providers passed Home, type, filter and category smoke tests.")
            appendLine("Posters appear after approval in Catalogue; this trust dialog has none.")
            if (fingerprint.kind == SpiderArtifactKind.PLAIN_DEX_PACKAGE) {
                appendLine("This bundle is an unguarded Android DEX: it is converted, not emulated.")
            }
            append("Select ABI:")
        }).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            rows = 16
            columns = 58
            background = this@ConfigurationPanel.background
        }
        val choice = JOptionPane.showConfirmDialog(
            this,
            JPanel(BorderLayout(6, 6)).apply {
                add(JScrollPane(warning), BorderLayout.CENTER)
                add(abi, BorderLayout.SOUTH)
            },
            if (reviewUpdate) "Approve updated provider bundle?" else "Trust these verified guarded sources?",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE,
        )
        if (choice != JOptionPane.OK_OPTION) {
            status.text = "Guarded sources were not enabled."
            return
        }
        val selectedAbi = GuardedSpiderAbi.parse(abi.selectedItem.toString())
        activate(batch, fingerprint.sha256, selectedAbi, remember = true)
    }

    private fun activateTrusted(trusted: List<Pair<DesktopTvBoxSite, GuardedSourceTrust>>, prefix: String) {
        val generation = loadGeneration
        val failures = mutableListOf<String>()
        val updatedKeys = mutableSetOf<String>()
        val selection = com.wuming.tv.desktop.config.DesktopCatalogSelectionStore(preferences)
        val preferredKey = selection.sourceKey(selection.rememberedSelection())
            ?: trusted.firstOrNull()?.first?.key
        setBusy(true, "Restoring ${trusted.size} exact guarded provider approvals…")
        object : SwingWorker<List<CatalogSourceExecutor>, Void>() {
            override fun doInBackground(): List<CatalogSourceExecutor> = restoreTrustedSources(
                trusted.mapNotNull { (site, trust) -> site.jvmExecution?.let { it to trust } },
                activate = { specs, trust ->
                    logger.info("restoring exact guarded trust sources=${specs.size} abi=${trust.abi.configName}")
                    spiderRuntime.activateAll(specs, trust)
                },
                preferredKey = preferredKey,
                onReady = { batch ->
                    SwingUtilities.invokeAndWait {
                        if (generation != loadGeneration) {
                            // The restoration worker releases this batch off the UI thread.
                            throw java.util.concurrent.CancellationException("Configuration changed during restoration")
                        }
                        onSourcesRestored(batch)
                        activeSpiderKeys.addAll(batch.map { it.source.key })
                        refreshSiteRows()
                    }
                },
                onFailure = { specs, failure ->
                    val names = specs.joinToString { it.name }
                    if (failure is SpiderArtifactChangedException) {
                        updatedKeys.addAll(specs.map { it.key })
                        logger.info("provider bundle update requires review sources=$names " +
                            "approvedSha256=${failure.approvedSha256} currentSha256=${failure.currentSha256}")
                    } else {
                        failures += "$names: ${rootMessage(failure)}"
                        logger.log(Level.WARNING, "guarded trust restoration failed sources=$names", failure)
                    }
                },
            )

            override fun done() {
                val result = getOrFailure()
                if (generation != loadGeneration) return
                updatedSpiderKeys.addAll(updatedKeys)
                reviewUpdated.isVisible = updatedSpiderKeys.isNotEmpty()
                refreshSiteRows()
                if (result.isSuccess && result.getOrThrow().size == trusted.size) {
                    configFingerprintStore.rememberActivatedFingerprint(configuration?.jsonFingerprint.orEmpty())
                }
                val updateNotice = if (updatedKeys.isEmpty()) "" else
                    " ${updatedKeys.size} sources have an updated bundle. Click Review updated bundle."
                val failureNotice = updateNotice + if (failures.isEmpty()) "" else
                    " Some sources need attention in Settings → Sites: ${failures.joinToString("; ")}."
                result.onSuccess { restored ->
                    setBusy(false, "${prefix}Restored ${restored.size} of ${trusted.size} desktop sources.$failureNotice")
                }.onFailure { setBusy(false, rootMessage(it)) }
            }
        }.execute()
    }

    private fun activate(
        sites: List<DesktopTvBoxSite>,
        trustedSha256: String,
        selectedAbi: GuardedSpiderAbi,
        remember: Boolean,
        prefix: String = "",
    ) {
        val specs = sites.mapNotNull(DesktopTvBoxSite::jvmExecution)
        if (specs.isEmpty()) return
        val generation = loadGeneration
        setBusy(true, "Preparing ${specs.size} isolated ${selectedAbi.configName} provider runtimes…")
        object : SwingWorker<List<CatalogSourceExecutor>, Void>() {
            override fun doInBackground(): List<CatalogSourceExecutor> =
                spiderRuntime.activateAll(specs, GuardedSourceTrust(trustedSha256, selectedAbi))

            override fun done() {
                val result = getOrFailure()
                if (generation != loadGeneration) {
                    result.getOrNull()?.forEach { runCatching { (it as? AutoCloseable)?.close() } }
                    return
                }
                if (result.isSuccess) {
                    val fingerprint = configuration?.jsonFingerprint.orEmpty()
                    if (remember) {
                        val activatedKeys = result.getOrThrow().map { it.source.key }.toSet()
                        specs.filter { it.key in activatedKeys }.forEach { trustStore.remember(it, trustedSha256, selectedAbi) }
                        configFingerprintStore.rememberActivatedFingerprint(fingerprint)
                    }
                }
                finishActivation(result, prefix, specs.size)
            }
        }.execute()
    }

    private fun <T> SwingWorker<T, Void>.getOrFailure(): Result<T> = runCatching { get() }

    private fun finishActivation(
        result: Result<List<CatalogSourceExecutor>>,
        prefix: String,
        expected: Int,
    ) {
        val executors = result.getOrElse {
            setBusy(false, rootMessage(it))
            return
        }
        runCatching { onGuardedEnabled(executors) }
            .onSuccess {
                activeSpiderKeys.addAll(executors.map { it.source.key })
                updatedSpiderKeys.removeAll(executors.map { it.source.key }.toSet())
                reviewUpdated.isVisible = updatedSpiderKeys.isNotEmpty()
                refreshSiteRows()
                setBusy(false, "${prefix}Enabled ${executors.size} of $expected desktop spider sources; VOD is loading.")
            }
            .onFailure { failure ->
                executors.forEach { (it as? AutoCloseable)?.let { closeable -> runCatching { closeable.close() } } }
                setBusy(false, rootMessage(failure))
            }
    }

    private fun setBusy(busy: Boolean, message: String) {
        reviewUpdated.isEnabled = !busy
        load.isEnabled = !busy
        forceReload.isEnabled = !busy
        enableGuarded.isEnabled = !busy && verifiedGuardedSites().isNotEmpty()
        checkSpider.isEnabled = !busy && !checkingSpider && configuration?.sites
            ?.getOrNull(sites.selectedIndex)?.let(::hasSpiderRuntime) == true
        depotVod.isEnabled = !busy
        location.isEnabled = !busy
        status.text = message
    }

    private fun TvBoxSourceRuntime.label(): String = when (this) {
        TvBoxSourceRuntime.DIRECT_JAVASCRIPT -> "direct JavaScript candidate"
        TvBoxSourceRuntime.SPIDER_RUNTIME_REQUIRED -> "spider runtime required"
        TvBoxSourceRuntime.MAC_CMS_API -> "MacCMS collection API"
        TvBoxSourceRuntime.REMOTE_SPIDER_HTTP -> "remote Spider HTTP"
        TvBoxSourceRuntime.UNSUPPORTED_TYPE -> "unsupported source type"
    }

    private fun rootMessage(error: Throwable): String {
        var current = error
        while (current.cause != null) current = current.cause!!
        val message = current.message ?: current.javaClass.simpleName
        val source = bundledLocation.value()
        return if (ProtectedSourceUrl.hasCredential(source)) message.replace(source, ProtectedSourceUrl.HIDDEN_LABEL)
            else message
    }

    private fun model(list: JList<String>): DefaultListModel<String> = list.model as DefaultListModel<String>

}
