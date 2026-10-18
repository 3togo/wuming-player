package com.wuming.tv.desktop.ui

import com.wuming.tv.core.live.LiveChannel
import com.wuming.tv.core.live.LivePlaylist
import com.wuming.tv.desktop.epg.DesktopProgrammeGuide
import com.wuming.tv.desktop.epg.DesktopProgrammeGuideLoader
import com.wuming.tv.desktop.live.DesktopLivePlaylistLoader
import com.wuming.tv.desktop.live.DesktopLiveStreamProbe
import com.wuming.tv.desktop.player.ExternalPlayerAdapter
import com.wuming.tv.desktop.config.DesktopTvBoxConfiguration
import com.wuming.tv.desktop.config.DesktopTvBoxLive
import com.wuming.tv.desktop.config.DesktopDepot
import com.wuming.tv.core.source.config.SourceBrowserEntry
import com.wuming.tv.core.source.config.SourceKind
import com.wuming.tv.core.source.config.SourceOrigin
import com.wuming.tv.core.source.config.ProtectedSourceUrl
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingWorker
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.filechooser.FileNameExtensionFilter
import java.util.logging.Level
import java.util.logging.Logger

class LiveTvPanel(
    private val player: ExternalPlayerAdapter?,
    private val loader: DesktopLivePlaylistLoader = DesktopLivePlaylistLoader(),
    private val guideLoader: DesktopProgrammeGuideLoader = DesktopProgrammeGuideLoader(),
    private val streamProbe: DesktopLiveStreamProbe = DesktopLiveStreamProbe(),
    private val library: com.wuming.tv.core.live.LiveLibraryPolicy = com.wuming.tv.core.live.LiveLibraryPolicy(com.wuming.tv.desktop.live.DesktopLiveLibraryStore()),
) : JPanel(BorderLayout(8, 10)), AutoCloseable {
    private val logger = Logger.getLogger("tvbox.ui.live")
    private val depotLives = DesktopDepot.load().live
    private val repeatRequests = RepeatRequestGate()

    private val location = JTextField()
    private val bundledLocation = DesktopBundledLocation(location)
    private val configuredSources = JComboBox<String>()
    private val filter = JTextField()
    private val groups = JList(DefaultListModel<String>())
    private val channels = JList(DefaultListModel<String>())
    private val routes = JComboBox<String>()
    private val status = JLabel("Load an M3U or TVBox TXT playlist to begin.")
    private val programmeStatus = JLabel("EPG: not loaded")
    private val favorite = JButton("Favorite")
    private val favoritesOnly = javax.swing.JCheckBox("Favorites only")
    private var librarySource = ""
    private var restoringSelection = false
    private val play = JButton("Play channel")
    private val load = JButton("Load")
    private var playlist: LivePlaylist? = null
    private var guide: DesktopProgrammeGuide? = null
    private var visibleChannels = emptyList<LiveChannel>()
    private var lastDirectory: File? = null
    @Volatile private var generation = 0
    @Volatile private var playbackRequest = 0
    private var preparingPlayback = false
    private var configuredLives = emptyList<DesktopTvBoxLive>()
    private var updatingConfiguredSources = false

    init {
        border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        groups.selectionMode = ListSelectionModel.SINGLE_SELECTION
        channels.selectionMode = ListSelectionModel.SINGLE_SELECTION
        groups.preferredSize = Dimension(190, 300)

        add(sourceBar(), BorderLayout.NORTH)
        add(channelBrowser(), BorderLayout.CENTER)
        add(actionBar(), BorderLayout.SOUTH)

        groups.addListSelectionListener { if (!it.valueIsAdjusting) refreshChannels() }
        channels.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                playbackRequest++
                preparingPlayback = false
                refreshRoutes()
            }
        }
        channels.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2) requestSelectedChannel()
            }
        })
        filter.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = refreshChannels()
            override fun removeUpdate(event: DocumentEvent) = refreshChannels()
            override fun changedUpdate(event: DocumentEvent) = refreshChannels()
        })
        configuredSources.addActionListener {
            val index = configuredSources.selectedIndex
            val source = configuredLives.getOrNull(index)
            if (!updatingConfiguredSources && source != null &&
                repeatRequests.accept("live-source:${source.location}")) {
                selectConfiguredSource(index)
            }
        }
        favorite.addActionListener {
            runCatching { selectedChannel()?.let { library.toggle(librarySource, it) } }.onSuccess {
                if (favoritesOnly.isSelected) refreshChannels() else updateActions()
            }.onFailure { status.text = "Could not save favorite. Check local settings storage." }
        }
        favoritesOnly.addActionListener { refreshChannels() }
        routes.addActionListener { rememberSelection() }
        play.addActionListener { requestSelectedChannel() }
        setConfiguredLives(depotLives)
        updateActions()
    }

    private fun sourceBar(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(JPanel(BorderLayout(8, 0)).apply {
            add(JLabel("Configured TV"), BorderLayout.WEST)
            add(configuredSources, BorderLayout.CENTER)
        })
        add(JPanel(BorderLayout(8, 0)).apply {
            border = BorderFactory.createEmptyBorder(6, 0, 0, 0)
            add(JLabel("Playlist URL or file"), BorderLayout.WEST)
            add(this@LiveTvPanel.location, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                add(JButton("Browse…").apply { addActionListener { chooseFile() } })
                add(JButton("Browse sources…").apply {
                    addActionListener {
                        val curated = depotLives.map { source ->
                            SourceBrowserEntry(source.name, source.location, SourceKind.LIVE, SourceOrigin.CURATED)
                        }
                        runCatching { DesktopOfflineLinkPicker.choose(this@LiveTvPanel, SourceKind.LIVE, curated) }
                            .onSuccess { selected ->
                                if (selected != null) {
                                    bundledLocation.select(selected)
                                    status.text = "${selected.name} selected. Choose Load to try it."
                                }
                            }.onFailure { status.text = it.message ?: "Could not open offline links." }
                    }
                })
                add(JButton("Edit URL…").apply {
                    addActionListener { bundledLocation.unlock(this@LiveTvPanel) }
                })
                add(load.apply {
                    addActionListener {
                        val source = bundledLocation.value()
                        if (repeatRequests.accept("live-playlist:$source")) loadPlaylist(forceRefresh = true)
                    }
                })
            }, BorderLayout.EAST)
        })
    }

    fun useLocalConfiguration(configuration: DesktopTvBoxConfiguration) {
        generation++
        setConfiguredLives(depotLives + configuration.lives)
        logger.info("configuration applied liveSources=${configuredLives.size}")
        if (configuredLives.isEmpty()) {
            load.isEnabled = true
            playlist = null
            guide = null
            clear(groups)
            clear(channels)
            routes.removeAllItems()
            status.text = "Configuration and depot contain no Live TV playlist."
            programmeStatus.text = "EPG: not loaded"
            updateActions()
        } else {
            selectConfiguredSource(0, tryNextOnFailure = true)
        }
    }

    private fun setConfiguredLives(sources: List<DesktopTvBoxLive>) {
        configuredLives = (listOf(CCTV_FALLBACK) + sources).distinctBy { it.location }
        updatingConfiguredSources = true
        configuredSources.removeAllItems()
        configuredLives.forEach { configuredSources.addItem(it.name) }
        configuredSources.selectedIndex = -1
        updatingConfiguredSources = false
        configuredSources.isEnabled = configuredLives.isNotEmpty()
    }

    private fun selectConfiguredSource(index: Int, tryNextOnFailure: Boolean = false) {
        val selected = configuredLives.getOrNull(index) ?: return
        updatingConfiguredSources = true
        configuredSources.selectedIndex = index
        updatingConfiguredSources = false
        bundledLocation.select(selected.location, selected.name)
        loadPlaylist("Loading configured TV: ${selected.name}…", verifyReachability = tryNextOnFailure) { error ->
            val next = index + 1
            if (tryNextOnFailure && next < configuredLives.size) {
                logger.info(
                    "configured playlist incompatible index=$index error=${rootMessage(error)}; trying index=$next",
                )
                status.text = "${selected.name} is not a portable playlist; trying the next configured TV source…"
                selectConfiguredSource(next, tryNextOnFailure = true)
                true
            } else {
                false
            }
        }
    }

    private fun channelBrowser(): JSplitPane {
        val channelPanel = JPanel(BorderLayout(6, 8)).apply {
            add(JPanel(BorderLayout(6, 0)).apply {
                add(JLabel("Filter"), BorderLayout.WEST)
                add(filter, BorderLayout.CENTER)
                add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                    add(favoritesOnly)
                    add(favorite)
                }, BorderLayout.SOUTH)
            }, BorderLayout.NORTH)
            add(JScrollPane(channels), BorderLayout.CENTER)
        }
        return JSplitPane(JSplitPane.HORIZONTAL_SPLIT, JScrollPane(groups), channelPanel).apply {
            resizeWeight = 0.22
            dividerLocation = 190
        }
    }

    private fun actionBar(): JPanel = JPanel(BorderLayout(8, 0)).apply {
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(status)
            add(programmeStatus)
        }, BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            add(JLabel("Route"))
            add(routes)
            add(play)
        }, BorderLayout.EAST)
    }

    private fun chooseFile() {
        val chooser = JFileChooser(lastDirectory).apply {
            dialogTitle = "Open live playlist"
            fileFilter = FileNameExtensionFilter("Live playlists (*.m3u, *.m3u8, *.txt)", "m3u", "m3u8", "txt")
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            lastDirectory = chooser.selectedFile.parentFile
            bundledLocation.manual(chooser.selectedFile.absolutePath)
            loadPlaylist()
        }
    }

    private fun loadPlaylist(
        message: String = "Loading playlist…",
        verifyReachability: Boolean = false,
        forceRefresh: Boolean = false,
        recover: ((Throwable) -> Boolean)? = null,
    ) {
        val source = bundledLocation.value()
        if (source.isEmpty()) {
            status.text = "Enter a playlist URL or choose a file."
            return
        }
        val expectedGeneration = ++generation
        load.isEnabled = false
        status.text = message
        logger.info("playlist load start generation=$expectedGeneration locationLength=${source.length}")
        object : SwingWorker<LoadedLivePlaylist, Void>() {
            override fun doInBackground(): LoadedLivePlaylist {
                val value = loader.load(source, forceRefresh)
                val preferred = if (verifyReachability) {
                    requireNotNull(streamProbe.firstReachableStream(value)) {
                        "Playlist parsed, but its first streams are unreachable"
                    }
                } else null
                return LoadedLivePlaylist(value, preferred?.url)
            }

            override fun done() {
                if (generation != expectedGeneration) {
                    logger.info("playlist load ignored stale generation=$expectedGeneration current=$generation")
                    return
                }
                load.isEnabled = true
                runCatching { get() }
                    .onSuccess { loaded ->
                        librarySource = library.sourceKey(source)
                        showPlaylist(loaded.playlist, loaded.preferredStreamUrl)
                        if (loader.cacheStatus == "stale") status.text += " (offline — stale playlist)"
                    }
                    .onFailure { error ->
                        if (recover?.invoke(error) == true) return@onFailure
                        playlist = null
                        guide = null
                        clear(groups)
                        clear(channels)
                        routes.removeAllItems()
                        status.text = "Load failed: ${rootMessage(error)}"
                        programmeStatus.text = "EPG: not loaded"
                        logger.log(
                            Level.WARNING,
                            "playlist load failed generation=$expectedGeneration error=${rootMessage(error)}",
                            error,
                        )
                        updateActions()
                    }
            }
        }.execute()
    }

    private fun showPlaylist(value: LivePlaylist, preferredStreamUrl: String? = null) {
        restoringSelection = true
        favoritesOnly.isSelected = false
        filter.text = ""
        playlist = value
        guide = null
        model(groups).apply {
            clear()
            value.groups.forEach { addElement("${it.name} (${it.channels.size})") }
        }
        val saved = library.selection(librarySource)
        val remembered = saved?.let { selection ->
            value.groups.withIndex().firstNotNullOfOrNull { (g, group) ->
                group.channels.indexOfFirst { library.channelKey(it) == selection.channel }.takeIf { it >= 0 }
                    ?.let { c -> PreferredLiveSelection(g, c, library.route(group.channels[c], selection)) }
            }
        }
        val preferred = remembered ?: preferredStreamUrl?.let { url ->
            value.groups.withIndex().firstNotNullOfOrNull { (groupIndex, group) ->
                group.channels.withIndex().firstNotNullOfOrNull { (channelIndex, channel) ->
                    channel.streams.indexOfFirst { it.url == url }.takeIf { it >= 0 }
                        ?.let { routeIndex -> PreferredLiveSelection(groupIndex, channelIndex, routeIndex) }
                }
            }
        }
        groups.selectedIndex = preferred?.groupIndex ?: 0
        preferred?.let {
            channels.selectedIndex = it.channelIndex
            routes.selectedIndex = it.routeIndex
        }
        restoringSelection = false
        status.text = "Loaded ${value.channelCount} channels in ${value.groups.size} groups." +
            if (saved != null && remembered == null) " Previous channel is unavailable; selected the first available channel." else ""
        logger.info(
            "playlist load success generation=$generation channels=${value.channelCount} groups=${value.groups.size} " +
                "verifiedSelection=${preferred != null}",
        )
        if (value.epgUrls.isEmpty()) {
            programmeStatus.text = "EPG: playlist has no guide URL"
        } else {
            programmeStatus.text = "Optional EPG: loading…"
            loadGuide(value.epgUrls, generation)
        }
        updateActions()
    }

    private fun loadGuide(locations: List<String>, expectedGeneration: Int, index: Int = 0) {
        val location = locations[index]
        object : SwingWorker<DesktopProgrammeGuide, Void>() {
            override fun doInBackground(): DesktopProgrammeGuide = guideLoader.load(location)

            override fun done() {
                if (generation != expectedGeneration) return
                runCatching { get() }
                    .onSuccess { result ->
                        guide = result
                        programmeStatus.text = "EPG: loaded ${result.programmeCount} programmes" +
                            if (guideLoader.cacheStatus == "stale") " (offline — stale guide)" else ""
                        refreshProgramme()
                    }
                    .onFailure { error ->
                        guide = null
                        val next = index + 1
                        if (next < locations.size) {
                            logger.info(
                                "optional EPG source failed index=$index error=${rootMessage(error)}; trying index=$next",
                            )
                            programmeStatus.text = "Optional EPG: trying another guide source…"
                            loadGuide(locations, expectedGeneration, next)
                        } else {
                            logger.info("optional EPG unavailable sources=${locations.size} error=${rootMessage(error)}")
                            programmeStatus.text = "Optional EPG unavailable; TV playback is ready."
                        }
                    }
            }
        }.execute()
    }

    private fun refreshChannels() {
        val source = playlist?.groups?.getOrNull(groups.selectedIndex)?.channels.orEmpty()
            .filter { !favoritesOnly.isSelected || library.favorite(librarySource, it) }
        val query = filter.text.trim()
        visibleChannels = if (query.isEmpty()) source else source.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.tvgName.contains(query, ignoreCase = true) || it.tvgId.contains(query, ignoreCase = true)
        }
        model(channels).apply {
            clear()
            visibleChannels.forEach { channel ->
                addElement(if (channel.number.isBlank()) channel.name else "${channel.number}  ${channel.name}")
            }
        }
        channels.selectedIndex = if (visibleChannels.isEmpty()) -1 else 0
        refreshRoutes()
    }

    private fun refreshRoutes() {
        val wasRestoring = restoringSelection
        restoringSelection = true
        routes.removeAllItems()
        selectedChannel()?.streams?.forEachIndexed { index, stream ->
            routes.addItem("${index + 1} · ${stream.url.substringBefore('?').take(48)}")
        }
        restoringSelection = wasRestoring
        rememberSelection()
        refreshProgramme()
        updateActions()
    }

    private fun refreshProgramme() {
        val currentGuide = guide ?: return
        val channel = selectedChannel() ?: return
        val schedule = currentGuide.nowNext(channel)
        val now = schedule.now
        val next = schedule.next
        programmeStatus.text = when {
            now != null && next != null -> "Now: ${now.title}  ·  Next: ${next.title}"
            now != null -> "Now: ${now.title}"
            next != null -> "Next: ${next.title}"
            else -> "EPG: no current programme for ${channel.name}"
        }
    }

    private fun playSelected() {
        val channel = selectedChannel() ?: return
        val adapter = player ?: return
        val preferredRoute = routes.selectedIndex.coerceAtLeast(0)
        val expectedGeneration = generation
        val expectedPlayback = ++playbackRequest
        preparingPlayback = true
        val channelTag = channel.name.replace(Regex("[\\p{Cntrl}]"), "").take(80)
        logger.info("play requested channel=$channelTag routes=${channel.streams.size} preferredRoute=$preferredRoute")
        play.isEnabled = false
        status.text = "Checking ${channel.name} routes…"
        object : SwingWorker<com.wuming.tv.core.live.LiveStream?, Void>() {
            override fun doInBackground() = streamProbe.selectStream(channel.streams, preferredRoute)

            override fun done() {
                if (generation != expectedGeneration || playbackRequest != expectedPlayback) {
                    updateActions()
                    return
                }
                val stream = runCatching { get() }.getOrNull()
                if (stream == null) {
                    logger.info("play unavailable channel=$channelTag checkedRoutes=${channel.streams.size.coerceAtMost(6)}")
                    preparingPlayback = false
                    status.text = "${channel.name}: no reachable route right now."
                } else {
                    val resolvedRoute = channel.streams.indexOf(stream)
                    resolvedRoute.takeIf { it >= 0 }?.let { routes.selectedIndex = it }
                    logger.info("play resolved channel=$channelTag route=$resolvedRoute")
                    status.text = "Opening ${channel.name} in ${adapter.name}…"
                    object : SwingWorker<com.wuming.tv.desktop.player.PlayerLaunchResult, Void>() {
                        override fun doInBackground() = adapter.play(
                            stream.url, stream.headers, displayName = channel.name,
                            isCancelled = { generation != expectedGeneration || playbackRequest != expectedPlayback },
                        )

                        override fun done() {
                            if (generation != expectedGeneration || playbackRequest != expectedPlayback) return
                            preparingPlayback = false
                            status.text = runCatching { get().message }
                                .getOrElse { "Could not open channel: ${rootMessage(it)}" }
                            updateActions()
                        }
                    }.execute()
                }
                updateActions()
            }
        }.execute()
    }

    private fun requestSelectedChannel() {
        val channel = selectedChannel() ?: return
        val route = routes.selectedIndex.coerceAtLeast(0)
        val stream = channel.streams.getOrNull(route)?.url.orEmpty()
        if (repeatRequests.accept("live-play:${channel.tvgId}:${channel.name}:$stream")) playSelected()
    }

    override fun close() {
        generation++
        playbackRequest++
        preparingPlayback = false
    }

    private fun selectedChannel(): LiveChannel? = visibleChannels.getOrNull(channels.selectedIndex)

    private fun rememberSelection() {
        if (!restoringSelection && librarySource.isNotEmpty()) selectedChannel()?.let {
            runCatching { library.remember(librarySource, it, routes.selectedIndex) }
                .onFailure { status.text = "Could not save channel selection. Check local settings storage." }
        }
    }

    private fun updateActions() {
        favorite.isEnabled = selectedChannel() != null
        favorite.text = if (selectedChannel()?.let { library.favorite(librarySource, it) } == true) "Unfavorite" else "Favorite"
        routes.isEnabled = selectedChannel() != null
        play.isEnabled = player != null && selectedChannel() != null && !preparingPlayback
    }

    private fun model(list: JList<String>): DefaultListModel<String> = list.model as DefaultListModel<String>

    private fun clear(list: JList<String>) = model(list).clear()

    private fun rootMessage(error: Throwable): String {
        var current = error
        while (current.cause != null) current = current.cause!!
        val message = current.message ?: current.javaClass.simpleName
        val source = bundledLocation.value()
        return if (ProtectedSourceUrl.hasCredential(source)) message.replace(source, ProtectedSourceUrl.HIDDEN_LABEL)
            else message
    }

    private data class LoadedLivePlaylist(
        val playlist: LivePlaylist,
        val preferredStreamUrl: String?,
    )

    private data class PreferredLiveSelection(
        val groupIndex: Int,
        val channelIndex: Int,
        val routeIndex: Int,
    )

    private companion object {
        val CCTV_FALLBACK = DesktopTvBoxLive(
            "CCTV · daily checked routes",
            "https://raw.githubusercontent.com/best-fan/iptv-sources/main/cn_cctv.m3u8",
        )
    }
}
