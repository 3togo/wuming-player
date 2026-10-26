package com.wuming.tv.desktop.ui

import com.wuming.tv.core.catalog.CatalogDetail
import com.wuming.tv.core.catalog.CatalogAuditCancellation
import com.wuming.tv.core.catalog.CatalogAuditReport
import com.wuming.tv.core.catalog.CatalogAuditStatus
import com.wuming.tv.core.catalog.CatalogAuditStep
import com.wuming.tv.core.catalog.CatalogGateway
import com.wuming.tv.core.catalog.CatalogFilterSelectionPolicy
import com.wuming.tv.core.catalog.CatalogFilterSelectionSession
import com.wuming.tv.core.catalog.CatalogItem
import com.wuming.tv.core.catalog.CatalogItemKind
import com.wuming.tv.core.catalog.CatalogLine
import com.wuming.tv.core.catalog.CatalogHome
import com.wuming.tv.core.catalog.CatalogPage
import com.wuming.tv.core.catalog.CatalogPlayback
import com.wuming.tv.core.catalog.CatalogSource
import com.wuming.tv.core.catalog.CatalogSourceRoster
import com.wuming.tv.core.catalog.CatalogSourceAuditor
import com.wuming.tv.core.catalog.CatalogSourceExecutor
import com.wuming.tv.core.catalog.RemoteSpiderCatalogSourceExecutor
import com.wuming.tv.core.catalog.CatalogType
import com.wuming.tv.core.catalog.ExecutorCatalogGateway
import com.wuming.tv.core.catalog.withSummaryFallback
import com.wuming.tv.core.maccms.MacCmsCatalogSourceExecutor
import com.wuming.tv.desktop.catalog.MacCmsHttpTransport
import com.wuming.tv.core.runtime.js.GraalJsCatalogSourceExecutor
import com.wuming.tv.desktop.js.DesktopJsLocalStorage
import com.wuming.tv.desktop.js.DesktopJsProxyServer
import com.wuming.tv.core.library.EpisodeAdvancePolicy
import com.wuming.tv.core.library.EpisodeSelectionPolicy
import com.wuming.tv.core.library.VideoLibraryEntry
import com.wuming.tv.core.catalog.LibraryDetailResolver
import com.wuming.tv.desktop.cloud.cloudAwareExecutor
import com.wuming.tv.desktop.config.DesktopCatalogSelectionStore
import com.wuming.tv.core.catalog.CatalogResultPager
import com.wuming.tv.core.catalog.CatalogPagination
import com.wuming.tv.desktop.config.DesktopCatalogSettings
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import com.wuming.tv.desktop.config.DesktopPlaybackSettings
import com.wuming.tv.desktop.config.DesktopTvBoxConfiguration
import com.wuming.tv.desktop.cloud.clearCatalogCache
import com.wuming.tv.desktop.cloud.DesktopCloudPlaybackResolver
import com.wuming.tv.desktop.library.DesktopUserLibrary
import com.wuming.tv.desktop.player.ExternalPlayerAdapter
import com.wuming.tv.desktop.player.PlaybackState
import com.wuming.tv.desktop.player.PlaybackStateCallback
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import com.wuming.tv.core.catalog.CatalogNavigationHistory
import com.wuming.tv.core.catalog.CatalogSourceCoordinator
import com.wuming.tv.core.catalog.CatalogSourcePayload
import com.wuming.tv.core.catalog.CatalogSourceOutcome
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.logging.Level
import java.util.logging.Logger
import java.util.prefs.Preferences
import kotlin.math.roundToInt
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.JSlider
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.JToggleButton
import javax.swing.ListSelectionModel
import javax.swing.ListCellRenderer
import javax.swing.SwingUtilities
import javax.swing.SwingWorker
import javax.swing.Timer
import javax.swing.UIManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.ListDataEvent
import javax.swing.event.ListDataListener

class CatalogPanel(
    private val player: ExternalPlayerAdapter?,
    preferences: Preferences = Preferences.userNodeForPackage(CatalogPanel::class.java),
    private val cloudPlaybackResolver: DesktopCloudPlaybackResolver? = null,
    private val userLibrary: DesktopUserLibrary = DesktopUserLibrary.openDefault(),
    private val onBackRequested: (() -> Unit)? = null,
) : JPanel(BorderLayout(8, 10)), AutoCloseable {
    private val logger = Logger.getLogger("tvbox.ui.catalog")
    private val selectionStore = DesktopCatalogSelectionStore(preferences)
    private val catalogSettings = DesktopCatalogSettings(preferences)
    private val playbackSettings = DesktopPlaybackSettings(preferences)
    private val searchHistoryStore = DesktopSearchHistoryStore(preferences)
    private val searchWordClient = DesktopSearchWordClient()
    private val repeatRequests = RepeatRequestGate()

    private val sources = JComboBox<CatalogSourceChoice>()
    private val pageTitle = JLabel("Discover").apply {
        font = font.deriveFont(Font.BOLD, 24f)
    }
    private val query = JTextField()
    private val search = JButton("Search")
    private val searchBack = JButton("← Back").apply {
        toolTipText = "Return to the previous search or page (Esc)"
        accessibleContext.accessibleName = "Go back from search"
        addActionListener {
            onBackRequested?.invoke() ?: navigateBack()
        }
    }
    private val recentKeywords = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))
    private val suggestedKeywords = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))
    private val keywordRails = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(keywordRail("Recent searches", recentKeywords))
        add(keywordRail("Suggestions", suggestedKeywords))
    }
    private val suggestionTimer = Timer(250) { refreshSuggestions() }.apply { isRepeats = false }
    private val home = JButton("Home")
    private val history = JButton("History")
    private val favorites = JButton("Keep")
    private val privateViewing = JCheckBox("Private viewing", playbackSettings.privateViewing)
    private val deleteHistory = JButton("Delete selected history")
    private val clearHistory = JButton("Clear history")
    /** Kept as the authoritative model; the visible control is the Leanback-style chip rail. */
    private val types = JComboBox<CatalogTypeChoice>()
    private val typeBar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 1))
    private val typeScroll = JScrollPane(
        typeBar,
        JScrollPane.VERTICAL_SCROLLBAR_NEVER,
        JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED,
    )
    private val filterBar = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val categoryBack = JButton("← Back")
    private val emptyState = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        rows = 3
        border = BorderFactory.createEmptyBorder(14, 14, 14, 14)
    }
    private val thumbnailsPerPage = JSpinner(SpinnerNumberModel(
        catalogSettings.thumbnailsPerPage, CatalogPagination.MIN_SIZE, CatalogPagination.MAX_SIZE, 1,
    )).apply {
        accessibleContext.accessibleName = "Videos per page marker"
        val field = (editor as JSpinner.NumberEditor).textField
        (field.formatter as javax.swing.text.NumberFormatter).commitsOnValidEdit = true
        field.toolTipText = "Sets page-marker spacing while the video list remains continuously scrollable."
    }
    private val thumbnailPrevious = JButton("‹ Previous page").apply { accessibleContext.accessibleName = "Previous video page" }
    private val thumbnailNext = JButton("Next page ›").apply { accessibleContext.accessibleName = "Next video page" }
    private val thumbnailPageLabel = JLabel()
    private val thumbnailSize = JSlider(
        DesktopCatalogSettings.MIN_THUMBNAIL_SCALE,
        DesktopCatalogSettings.MAX_THUMBNAIL_SCALE,
        catalogSettings.thumbnailScalePercent,
    ).apply {
        accessibleContext.accessibleName = "Thumbnail size"
        toolTipText = "Adjust video card size relative to the catalogue area"
    }
    private val thumbnailSizeLabel = JLabel("${thumbnailSize.value}%")
    private var thumbnailPage = 0
    private var allItems = emptyList<CatalogItem>()
    private val results = JList(DefaultListModel<CatalogItem>())
    private val resultsScroll = JScrollPane(results).apply {
        border = BorderFactory.createEmptyBorder()
        viewport.background = results.background
        verticalScrollBar.unitIncrement = 28
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    }
    private val details = JTextArea()
    private val detailPoster = JLabel()
    private val browserCards = java.awt.CardLayout()
    private val browserContent = JPanel(browserCards)
    private val detailBack = JButton("← Back to catalogue")
    private val lines = JComboBox<CatalogLine>()
    private val episodes = JList(DefaultListModel<String>())
    private val audit = JButton("Audit source")
    private val stopAudit = JButton("Stop audit")
    private val loadDetails = JButton("Details")
    private val previous = JButton("Previous")
    private val play = JButton("Play episode")
    private val next = JButton("Next")
    private val replay = JButton("↻ Replay")
    private val playerPrevious = JButton("⏮")
    private val playerNext = JButton("⏭")
    private val playerStop = JButton("■ Stop")
    private val playerPause = JButton("❚❚ Pause")
    private val speedButton = JButton("1.0x")
    private val subtitleButton = JButton("Sub")
    private val keep = JButton("Favorite")
    private val episodeActions = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
    private val playerActions = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
    private val auditActions = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isVisible = false }
    private val autoNext = JCheckBox("Play next automatically", playbackSettings.autoPlayNextEpisode)
    private val progressBar = JProgressBar(0, 3).apply {
        isIndeterminate = false
        isVisible = false
        isBorderPainted = false
        preferredSize = Dimension(72, 4)
        minimumSize = preferredSize
        maximumSize = preferredSize
        toolTipText = "Completed startup stages: resolve video, open player, start playback."
        accessibleContext.accessibleName = "Completed playback startup stages"
    }
    private val searchFields = JPanel(BorderLayout(8, 6)).apply {
        add(JPanel(BorderLayout(8, 0)).apply {
            add(searchBack, BorderLayout.WEST)
            add(query, BorderLayout.CENTER)
            add(search, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(keywordRails, BorderLayout.CENTER)
    }
    private val historyActions = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
        add(deleteHistory)
        add(clearHistory)
        isVisible = false
    }
    private val vodControls = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            add(home)
            add(history)
            add(favorites)
            add(categoryBack)
            add(privateViewing)
        })
        add(historyActions)
        add(typeScroll.apply {
            border = BorderFactory.createEmptyBorder(4, 0, 2, 0)
            preferredSize = Dimension(100, TYPE_RAIL_HEIGHT)
            horizontalScrollBar.unitIncrement = 28
        })
    }
    private val status = JLabel("Load a TVBox URL in Configuration to use local desktop sources.")
    private var catalog: CatalogGateway? = null
    @Volatile private var catalogGeneration = 0L
    private var items = emptyList<CatalogItem>()
    private var activeViewing: ActiveViewing? = null
    private var detail: CatalogDetail? = null
    private var detailPosterGeneration = 0L
    @Volatile private var playbackGeneration = 0L
    private var browseGeneration = 0L
    private var homeLoadGeneration = 0L
    private var renderedHomeRequest: Long? = null
    private var configuredSiteCount = 0
    private var configuredSearchableSiteCount = 0
    private var configuredSourceCapabilities = emptyMap<String, CatalogSource>()
    private var unavailableSourceReasons = emptyMap<String, String>()
    private var auditWorker: SwingWorker<CatalogAuditReport, CatalogAuditStep>? = null
    private val localExecutors = linkedMapOf<String, CatalogSourceExecutor>()
    private val thumbnails = CatalogThumbnailCache()
    private var jsProxyServer: DesktopJsProxyServer? = null
    private val executorCleanup = Executors.newSingleThreadExecutor { task ->
        Thread(task, "tvbox-catalog-cleanup").apply { isDaemon = false }
    }
    @Volatile private var closed = false
    @Volatile private var resolving = false
    private var prewarmedUrl: String? = null
    private var prewarmedHeaders: Map<String, String> = emptyMap()
    private var prewarmedPlayback: Long = 0L
    private val speedOptions = doubleArrayOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 3.0)
    private var speedIndex = 2
    private val playbackStartup = JLabel().apply { isVisible = false }
    private var playbackStartupNanos = 0L
    private var playbackStartupStage = ""
    private var playbackStartupStep = 1
    private val playbackStartupTimer = Timer(250) { refreshPlaybackStartup() }
    private val autoPlayTimer = Timer(2000) { autoPlayFirstEpisode() }.apply { isRepeats = false }
    private val sourceOperations = Executors.newFixedThreadPool(MAX_CONCURRENT_SOURCES) { task ->
        Thread(task, "tvbox-catalog-source").apply { isDaemon = true }
    }
    private val sourceCoordinator = CatalogSourceCoordinator(sourceOperations)
    private val automaticHomeLoad = Timer(AUTOMATIC_HOME_SETTLE_MILLIS) { loadHome() }.apply {
        isRepeats = false
    }
    private var updatingSources = false
    private var updatingBrowseControls = false
    private var currentCategoryType: CatalogType? = null
    private var currentCategorySourceKey: String? = null
    private var currentCategoryId: String? = null
    private var currentCategoryPage: CatalogPage? = null
    private var categoryLoading = false
    private var categoryPager = CatalogResultPager()
    private var categoryPagerScope: Triple<String, String, Map<String, String>>? = null
    private var categoryPagerGeneration = -1L
    private val categorySelections = linkedMapOf<String, String>()
    private val filterSelections = CatalogFilterSelectionSession()
    private val visibleFilterScopes = mutableSetOf<Pair<String, String>>()
    private val categoryStack = mutableListOf<CategoryState>()
    private val vodBrowseHistory = CatalogNavigationHistory<BrowseSnapshot>()
    private val searchBrowseHistory = CatalogNavigationHistory<BrowseSnapshot>()
    private val browseHistory: CatalogNavigationHistory<BrowseSnapshot>
        get() = if (pageMode == CatalogPageMode.SEARCH) searchBrowseHistory else vodBrowseHistory
    private var showingDetails = false
    private var pageMode = CatalogPageMode.VOD
    private var vodSnapshot: BrowseSnapshot? = null
    private var searchSnapshot: BrowseSnapshot? = null

    init {
        userLibrary.privacy.privateViewing = privateViewing.isSelected
        border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        results.selectionMode = ListSelectionModel.SINGLE_SELECTION
        results.layoutOrientation = JList.HORIZONTAL_WRAP
        results.visibleRowCount = -1
        results.fixedCellWidth = DesktopTvTheme.px(CatalogItemRenderer.BASE_CARD_WIDTH)
        results.fixedCellHeight = DesktopTvTheme.px(CatalogItemRenderer.BASE_CARD_HEIGHT)
        results.font = results.font.deriveFont(14f)
        episodes.selectionMode = ListSelectionModel.SINGLE_SELECTION
        episodes.layoutOrientation = JList.HORIZONTAL_WRAP
        // Let the viewport width determine the column count. A positive row count makes Swing
        // keep creating off-screen columns, which is especially bad when horizontal scrolling is disabled.
        episodes.visibleRowCount = 0
        episodes.fixedCellWidth = EPISODE_CELL_WIDTH
        episodes.fixedCellHeight = EPISODE_CELL_HEIGHT
        episodes.font = episodes.font.deriveFont(14f)
        episodes.cellRenderer = EpisodeRenderer()
        details.isEditable = false
        details.lineWrap = true
        details.wrapStyleWord = true
        details.rows = 7
        details.font = details.font.deriveFont(15f)
        query.font = query.font.deriveFont(15f)
        sources.font = sources.font.deriveFont(14f)
        listOf(
            search, home, history, favorites, categoryBack,
            audit, stopAudit, loadDetails, detailBack, keep, previous, play, replay, next,
            playerPrevious, playerNext, playerStop, playerPause, speedButton, subtitleButton,
        ).forEach { button ->
            button.font = button.font.deriveFont(Font.BOLD, 13f)
            button.margin = Insets(6, 12, 6, 12)
        }
        sources.renderer = NamedRenderer<CatalogSourceChoice> { choice ->
            when {
                choice == null -> ""
                else -> choice.source?.key?.let(::unavailableReason)?.let { "${choice.name} · $it" } ?: choice.name
            }
        }
        types.renderer = NamedRenderer<CatalogTypeChoice> { it?.name.orEmpty() }
        lines.renderer = NamedRenderer<CatalogLine> { item ->
            item?.let { com.wuming.tv.core.catalog.CatalogLineNames.display(it.name, it.flag) }.orEmpty()
        }
        results.cellRenderer = CatalogItemRenderer(thumbnails, { results.repaint() }) { key ->
            if ((sources.selectedItem as? CatalogSourceChoice)?.isShowAll == true) sourceName(key) else ""
        }
        resultsScroll.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) = updateResponsiveCardSize()
        })
        results.model.addListDataListener(object : ListDataListener {
            override fun intervalAdded(event: ListDataEvent) = updateResponsiveCardSizeLater()
            override fun intervalRemoved(event: ListDataEvent) = updateResponsiveCardSizeLater()
            override fun contentsChanged(event: ListDataEvent) = updateResponsiveCardSizeLater()
        })
        detailPoster.horizontalAlignment = JLabel.CENTER
        detailPoster.verticalAlignment = JLabel.TOP
        detailPoster.preferredSize = Dimension(CatalogThumbnailCache.WIDTH + 16, CatalogThumbnailCache.HEIGHT)
        detailPoster.toolTipText = "Double-click to load details"
        detailPoster.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2 && loadDetails.isEnabled) requestSelectedItem()
            }
        })

        add(browser(), BorderLayout.CENTER)
        add(actionBar(), BorderLayout.SOUTH)

        thumbnailPrevious.addActionListener {
            val target = thumbnailPage - 1
            val size = (thumbnailsPerPage.value as Number).toInt()
            if (repeatRequests.accept("thumbnail-page:$size:$target")) scrollToThumbnailPage(target)
        }
        thumbnailNext.addActionListener {
            val target = thumbnailPage + 1
            val size = (thumbnailsPerPage.value as Number).toInt()
            if (repeatRequests.accept("thumbnail-page:$size:$target")) navigateToThumbnailPage(target)
        }
        thumbnailsPerPage.addChangeListener {
            catalogSettings.thumbnailsPerPage = (thumbnailsPerPage.value as Number).toInt()
            thumbnailPage = 0
            if (currentCategoryType != null && currentCategoryId != null) loadCategory(1)
            else renderThumbnailPage()
        }
        thumbnailSize.addChangeListener {
            thumbnailSizeLabel.text = "${thumbnailSize.value}%"
            updateResponsiveCardSize()
            if (!thumbnailSize.valueIsAdjusting) {
                catalogSettings.thumbnailScalePercent = thumbnailSize.value
            }
        }
        renderThumbnailPage()
        search.addActionListener { requestSearch() }
        home.addActionListener { if (repeatRequests.accept(homeRequestKey())) loadHome() }
        privateViewing.toolTipText = "Do not save playback progress or recommendation history. Favorites remain available."
        privateViewing.addActionListener {
            playbackSettings.privateViewing = privateViewing.isSelected
            userLibrary.privacy.privateViewing = privateViewing.isSelected
            status.text = if (privateViewing.isSelected) "Private viewing: progress and recommendations will not be saved." else "History recording resumes with the next playback session."
        }
        deleteHistory.addActionListener {
            val item = results.selectedValue
            if (item == null) status.text = "Select a history item first."
            else {
                runCatching { userLibrary.deleteHistory(item) }.onSuccess {
                    loadLibrary(favoritesOnly = false, rememberNavigation = false)
                    status.text = "Deleted history and recommendation signals for ${item.name}."
                }.onFailure { status.text = "History could not be fully deleted. Check local storage and retry." }
            }
        }
        clearHistory.addActionListener {
            if (javax.swing.JOptionPane.showConfirmDialog(this,
                    "Delete all viewing history and recommendation signals? Favorites are kept.",
                    "Clear history", javax.swing.JOptionPane.OK_CANCEL_OPTION) == javax.swing.JOptionPane.OK_OPTION) {
                runCatching { userLibrary.clearHistory() }.onSuccess {
                    loadLibrary(favoritesOnly = false, rememberNavigation = false)
                }.onFailure { status.text = "History could not be fully cleared. Check local storage and retry." }
            }
        }
        history.addActionListener { if (repeatRequests.accept("library:history")) loadLibrary(favoritesOnly = false) }
        favorites.addActionListener { if (repeatRequests.accept("library:keep")) loadLibrary(favoritesOnly = true) }
        query.addActionListener { requestSearch() }
        query.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = scheduleSuggestions()
            override fun removeUpdate(event: DocumentEvent) = scheduleSuggestions()
            override fun changedUpdate(event: DocumentEvent) = scheduleSuggestions()
        })
        sources.addActionListener {
            updateActions()
            if (!updatingSources) {
                val choice = sources.selectedItem as? CatalogSourceChoice
                if (repeatRequests.accept("source:${pageMode.name}:${choice?.source?.key}:${choice?.isShowAll}")) {
                    rememberSourceSelection()
                    clearCategoryBrowsing(clearTypes = true)
                    if (pageMode == CatalogPageMode.VOD) loadHome() else showUnavailableSelection()
                }
            }
        }
        types.addActionListener {
            val choice = types.selectedItem as? CatalogTypeChoice
            if (!updatingBrowseControls && choice != null &&
                repeatRequests.accept("type:${choice.sourceKey}:${choice.type.id}")) {
                selectCategoryType(choice)
            }
        }
        categoryBack.addActionListener { navigateBack() }
        resultsScroll.verticalScrollBar.addAdjustmentListener { event ->
            if (!event.valueIsAdjusting) updateVisibleThumbnailPage(loadMoreAtEnd = true)
        }
        results.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2) requestSelectedItem()
            }
        })
        results.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                previewSelection()
                updateActions()
            }
        }
        lines.addActionListener { refreshEpisodes() }
        episodes.addListSelectionListener { if (!it.valueIsAdjusting) updateActions() }
        episodes.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2) requestEpisodePlayback()
            }
        })
        play.addActionListener { requestEpisodePlayback() }
        previous.addActionListener { moveEpisode(-1) }
        next.addActionListener { moveEpisode(1) }
        replay.addActionListener { replayEpisode() }
        playerStop.addActionListener { stopExternalPlayer() }
        playerPrevious.addActionListener { transport(-1) }
        playerNext.addActionListener { transport(1) }
        playerPause.addActionListener { player?.togglePause() }
        speedButton.addActionListener { cycleSpeed() }
        subtitleButton.addActionListener { loadSubtitle() }
        keep.addActionListener { toggleFavorite() }
        autoNext.addActionListener { playbackSettings.autoPlayNextEpisode = autoNext.isSelected }
        loadDetails.addActionListener {
            requestSelectedItem {
                localExecutors.values.forEach(::clearCatalogCache)
                activateSelection()
            }
        }
        detailBack.addActionListener {
            showBrowsePane()
            results.requestFocusInWindow()
        }
        audit.addActionListener { auditSource() }
        stopAudit.addActionListener { auditWorker?.cancel(true) }
        applyPageMode()
        rebuildRecentKeywords()
        updateActions()
    }

    private fun updateResponsiveCardSizeLater() {
        SwingUtilities.invokeLater(::updateResponsiveCardSize)
    }

    /** Size cards from the viewport, so loading more results never makes existing cards shrink. */
    private fun updateResponsiveCardSize() {
        val width = resultsScroll.viewport.extentSize.width
        if (width <= 0) return

        val aspect = CatalogItemRenderer.BASE_CARD_WIDTH.toDouble() / CatalogItemRenderer.BASE_CARD_HEIGHT
        val cellWidth = CatalogThumbnailSizing.cellWidth(width, thumbnailSize.value)
        val cellHeight = (cellWidth / aspect).toInt()
        if (results.fixedCellWidth == cellWidth && results.fixedCellHeight == cellHeight) return

        results.fixedCellWidth = cellWidth
        results.fixedCellHeight = cellHeight
        results.revalidate()
        results.repaint()
    }

    private fun browser(): JPanel = JPanel(BorderLayout(8, 8)).apply {
        add(JPanel(BorderLayout(0, 10)).apply {
            add(JPanel(BorderLayout(0, 6)).apply {
                add(JPanel(BorderLayout(16, 0)).apply {
                    add(pageTitle, BorderLayout.WEST)
                    sources.preferredSize = Dimension(340, 36)
                    add(sources, BorderLayout.CENTER)
                    add(JButton("Refresh").apply {
                        toolTipText = "Refresh the selected site's catalogue and episode lists"
                        addActionListener {
                            val key = (sources.selectedItem as? CatalogSourceChoice)?.source?.key
                            val requestKey = "refresh:${pageMode.name}:$key:${query.text.trim()}"
                            if (repeatRequests.accept(requestKey)) {
                                localExecutors.filterKeys { key == null || it == key }.values
                                    .forEach(::clearCatalogCache)
                                vodSnapshot = null
                                searchSnapshot = null
                                renderedHomeRequest = null
                                thumbnails.retryFailed()
                                if (pageMode == CatalogPageMode.SEARCH) search(false) else loadHome()
                            }
                        }
                    }, BorderLayout.EAST)
                }, BorderLayout.NORTH)
                add(searchFields, BorderLayout.CENTER)
                add(vodControls, BorderLayout.SOUTH)
            }, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        status.addPropertyChangeListener("text") { updateStatusPresentation() }
        updateStatusPresentation()
        val resultPanel = JPanel(BorderLayout(6, 8)).apply {
            add(JPanel(BorderLayout()).apply {
                add(emptyState, BorderLayout.CENTER)
                add(JPanel(BorderLayout(12, 0)).apply {
                    add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
                        add(JLabel("Thumbnail size:"))
                        add(thumbnailSize)
                        add(thumbnailSizeLabel)
                    }, BorderLayout.WEST)
                    add(JPanel(FlowLayout(FlowLayout.RIGHT, 8, 2)).apply {
                        add(JLabel("Page size:"))
                        add(thumbnailsPerPage)
                        add(thumbnailPrevious)
                        add(thumbnailPageLabel)
                        add(thumbnailNext)
                    }, BorderLayout.EAST)
                }, BorderLayout.SOUTH)
            }, BorderLayout.SOUTH)
            add(filterBar, BorderLayout.NORTH)
            add(resultsScroll, BorderLayout.CENTER)
        }
        val detailOverview = JPanel(BorderLayout(8, 0)).apply {
                border = BorderFactory.createEmptyBorder(4, 4, 16, 4)
                add(detailBack, BorderLayout.NORTH)
                add(JPanel(BorderLayout(16, 0)).apply {
                    border = BorderFactory.createEmptyBorder(16, 0, 0, 0)
                    add(detailPoster, BorderLayout.WEST)
                    add(JScrollPane(details).apply { border = BorderFactory.createEmptyBorder() }, BorderLayout.CENTER)
                }, BorderLayout.CENTER)
                minimumSize = Dimension(0, DETAIL_OVERVIEW_MIN_HEIGHT)
            }
        val episodeScroll = JScrollPane(episodes).apply {
            verticalScrollBar.unitIncrement = 22
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            preferredSize = Dimension(100, EPISODE_BROWSER_HEIGHT)
            viewport.addComponentListener(object : java.awt.event.ComponentAdapter() {
                override fun componentResized(event: java.awt.event.ComponentEvent) {
                    episodes.fixedCellWidth = EpisodeGridSizing.cellWidth(
                        viewport.extentSize.width,
                        EPISODE_CELL_WIDTH,
                    )
                }
            })
        }
        val episodeBrowser = JPanel(BorderLayout(6, 6)).apply {
                add(lines, BorderLayout.NORTH)
                add(episodeScroll, BorderLayout.CENTER)
                minimumSize = Dimension(0, EPISODE_BROWSER_HEIGHT)
            }
        val detailPanel = JPanel(BorderLayout(6, 6)).apply {
            add(javax.swing.JSplitPane(
                javax.swing.JSplitPane.VERTICAL_SPLIT,
                detailOverview,
                episodeBrowser,
            ).apply {
                resizeWeight = 0.58
                dividerSize = 6
                border = BorderFactory.createEmptyBorder()
            }, BorderLayout.CENTER)
        }
        browserContent.add(resultPanel, BROWSER_RESULTS)
        browserContent.add(detailPanel, BROWSER_DETAILS)
        add(browserContent, BorderLayout.CENTER)
    }

    private fun keywordRail(title: String, content: JPanel): JPanel = JPanel(BorderLayout(8, 0)).apply {
        add(JLabel(title).apply { font = font.deriveFont(Font.BOLD, 13f) }, BorderLayout.WEST)
        add(content, BorderLayout.CENTER)
    }

    private fun scheduleSuggestions() {
        suggestionTimer.restart()
    }

    private fun refreshSuggestions() {
        searchWordClient.requestSuggestions(query.text) { rebuildKeywordButtons(suggestedKeywords, it, removable = false) }
    }

    private fun rebuildRecentKeywords(items: List<String> = searchHistoryStore.recent()) {
        rebuildKeywordButtons(recentKeywords, items, removable = true)
    }

    private fun rebuildKeywordButtons(target: JPanel, words: List<String>, removable: Boolean) {
        target.removeAll()
        words.forEach { word ->
            target.add(JButton(word).apply {
                margin = Insets(3, 9, 3, 9)
                isFocusable = true
                toolTipText = if (removable) "Click to search; right-click to remove" else "Search for $word"
                addActionListener { searchKeyword(word) }
                if (removable) addMouseListener(object : MouseAdapter() {
                    override fun mousePressed(event: MouseEvent) {
                        if (event.isPopupTrigger || event.button == MouseEvent.BUTTON3) {
                            rebuildRecentKeywords(searchHistoryStore.remove(word))
                        }
                    }
                })
            })
        }
        target.isVisible = words.isNotEmpty()
        (target.parent as? JPanel)?.isVisible = words.isNotEmpty()
        keywordRails.revalidate()
        keywordRails.repaint()
    }

    private fun searchKeyword(word: String) {
        query.text = word
        requestSearch()
    }

    private fun requestSearch() {
        val source = (sources.selectedItem as? CatalogSourceChoice)?.source?.key.orEmpty()
        val keyword = query.text.trim()
        if (repeatRequests.accept("search:$source:$keyword")) search()
    }

    private fun homeRequestKey(): String {
        val choice = sources.selectedItem as? CatalogSourceChoice
        return "home:${choice?.source?.key}:${choice?.isShowAll}"
    }

    private fun requestSelectedItem(request: () -> Unit = ::activateSelection) {
        val item = items.getOrNull(results.selectedIndex) ?: return
        if (repeatRequests.accept("item:${item.sourceKey}:${item.kind}:${item.id}")) request()
    }

    private fun requestEpisodePlayback() {
        val item = detail?.item ?: return
        val line = lines.selectedItem as? CatalogLine ?: return
        val episode = line.episodes.getOrNull(episodes.selectedIndex) ?: return
        if (repeatRequests.accept("play:${item.sourceKey}:${item.id}:${line.flag}:${episode.id}")) playEpisode()
    }

    private fun actionBar(): JPanel = JPanel(BorderLayout(0, 6)).apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, DesktopTvTheme.TILE),
            BorderFactory.createEmptyBorder(6, 0, 0, 0),
        )
        add(JPanel(BorderLayout(0, 4)).apply {
            add(status, BorderLayout.NORTH)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                add(progressBar)
                add(playbackStartup)
            }, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JPanel(BorderLayout()).apply {
                add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                    add(loadDetails)
                    add(keep)
                }, BorderLayout.WEST)
                add(JButton("Source tools").apply {
                    toolTipText = "Show source audit controls"
                    addActionListener { auditActions.isVisible = !auditActions.isVisible; revalidate() }
                }, BorderLayout.EAST)
            })
            episodeActions.apply { add(previous); add(play); add(next); add(replay); add(autoNext) }
            playerActions.apply {
                add(playerPrevious); add(playerPause); add(playerNext); add(playerStop)
                add(speedButton); add(subtitleButton)
            }
            auditActions.apply { add(audit); add(stopAudit) }
            add(episodeActions)
            add(playerActions)
            add(auditActions)
        }, BorderLayout.CENTER)
    }

    fun showConfigurationStatus(message: String) {
        if (configuredSiteCount == 0) status.text = message
    }

    fun useLocalConfiguration(configuration: DesktopTvBoxConfiguration) {
        vodSnapshot = null
        searchSnapshot = null
        vodBrowseHistory.clear()
        searchBrowseHistory.clear()
        configuredSiteCount = configuration.sites.size
        configuredSearchableSiteCount = configuration.searchableSites.size
        configuredSourceCapabilities = configuration.sites.associate { site ->
            site.key to CatalogSource(site.key, site.name, site.searchable, site.indexable)
        }
        unavailableSourceReasons = configuration.sites.associate { site ->
            site.key to when {
                site.inputOnly -> "input handler · no video catalogue"
                !site.browseEnabled -> "hidden by configuration"
                site.jvmExecution != null -> "enable or check in Settings"
                site.pythonExecution != null -> "native Python runtime not packaged"
                site.execution != null || site.apiExecution != null || site.remoteExecution != null -> "runtime not active"
                else -> "unsupported desktop runtime"
            }
        }
        val nextJsStorageIds = configuration.directJavaScriptSites.mapNotNull { site ->
            site.execution?.let { site.key + "\u0000" + it.storageIdentity }
        }.toSet()
        retireLocalExecutors()
        // Switching subscriptions must not erase state belonging to other installed packages.
        val installedJsStorageIds = runCatching {
            com.wuming.tv.desktop.packages.DesktopSourcePackages.retainedJsStorageIdentities()
        }.getOrDefault(emptySet())
        DesktopJsLocalStorage.reconcileDefault(nextJsStorageIds + installedJsStorageIds)
        val proxyServer = if (configuration.directJavaScriptSites.isEmpty()) {
            jsProxyServer?.close()
            jsProxyServer = null
            null
        } else {
            jsProxyServer ?: DesktopJsProxyServer().also { jsProxyServer = it }
        }
        val macCmsTransport = MacCmsHttpTransport()
        val executors = configuration.directJavaScriptSites.mapNotNull { site ->
            site.execution?.let { spec ->
                val storageId = site.key + "\u0000" + spec.storageIdentity
                val registration = checkNotNull(proxyServer).register(site.key)
                val executor = GraalJsCatalogSourceExecutor(
                    spec,
                    moduleLoader = com.wuming.tv.desktop.cache.DesktopModuleResources::load,
                    localStorage = DesktopJsLocalStorage.openDefault(storageId),
                    proxyUrlFactory = registration,
                    onClose = registration::close,
                )
                registration.attach { segments, headers, requestHeaders ->
                    executor.proxy(segments, headers, requestHeaders)
                }
                executor
            }
        } + configuration.macCmsSites.mapNotNull { site ->
            site.apiExecution?.let { MacCmsCatalogSourceExecutor(it, macCmsTransport) }
        } + configuration.remoteSpiderSites.mapNotNull { site ->
            site.remoteExecution?.let { RemoteSpiderCatalogSourceExecutor(it, macCmsTransport) }
        }
        val pythonExecutors = if (com.wuming.tv.desktop.python.DesktopPythonRuntime.available()) {
            configuration.browsableSites.mapNotNull { it.pythonExecution }.map { com.wuming.tv.desktop.python.DesktopPythonRuntime.executor(it) }
        } else emptyList()
        (executors + pythonExecutors).forEach { localExecutors[it.source.key] = cloudAware(it) }
        refreshLocalGateway()
        val available = catalog?.sources().orEmpty()
        allItems = emptyList()
        thumbnailPage = 0
        renderThumbnailPage()
        detail = null
        clearCategoryBrowsing(clearTypes = true)
        model(results).clear()
        clearDetails()
        status.text = if (available.isEmpty()) {
            "No locally executable sources were found."
        } else {
            "Local desktop runtime: ${available.size} browse-capable sources."
        }
        updateActions()
        if (available.isNotEmpty() && pageMode == CatalogPageMode.VOD) scheduleAutomaticHomeLoad()
    }

    fun addLocalExecutor(executor: CatalogSourceExecutor) {
        addLocalExecutors(listOf(executor))
    }

    fun addLocalExecutors(
        executors: Collection<CatalogSourceExecutor>,
        replaceExisting: Boolean = false,
        preserveBrowse: Boolean = false,
    ) {
        val selectedKey = (sources.selectedItem as? CatalogSourceChoice)?.source?.key
        val selectedWasReady = selectedKey != null && localExecutors.containsKey(selectedKey)
        val additive = !replaceExisting && executors.none { localExecutors.containsKey(it.source.key) }
        if (replaceExisting) retireLocalExecutors()
        executors.forEach { executor ->
            localExecutors.remove(executor.source.key)?.let(::retireExecutor)
            localExecutors[executor.source.key] = cloudAware(executor)
            logger.info("executor enabled key=${executor.source.key} name=${executor.source.name}")
        }
        refreshLocalGateway(selectedKey, preserveInFlight = preserveBrowse && additive)
        if (preserveBrowse && selectedWasReady && additive) return
        status.text = "Enabled ${executors.size} guarded sources for this session."
        automaticHomeLoad.stop()
        if (pageMode == CatalogPageMode.VOD) loadHome()
    }

    private fun cloudAware(executor: CatalogSourceExecutor): CatalogSourceExecutor =
        cloudAwareExecutor(executor, cloudPlaybackResolver)

    fun focusSearch() {
        // Search is a separate Leanback destination. Invalidate an in-flight Home request so
        // it cannot repaint this page after the navigation click.
        if (pageMode != CatalogPageMode.SEARCH) {
            vodSnapshot = currentSnapshot()
            automaticHomeLoad.stop()
            browseGeneration++
            renderedHomeRequest = null
            pageMode = CatalogPageMode.SEARCH
            applyPageMode()
            searchSnapshot?.let(::restoreBrowseSnapshot) ?: run {
                showBrowsePane()
                clearCategoryBrowsing(clearTypes = true)
                browseHistory.clear()
                showItems(emptyList())
                status.text = if (catalog == null) {
                    "Load a TVBox configuration in Setting, then enter a title to search."
                } else {
                    "Enter a title and press Search."
                }
            }
        }
        query.selectAll()
        query.requestFocusInWindow()
    }

    fun showLibrary() {
        loadLibrary(favoritesOnly = false)
    }

    fun showKeep() {
        pageTitle.text = "Keep"
        loadLibrary(favoritesOnly = true)
    }

    fun focusBrowse() {
        autoNext.isSelected = playbackSettings.autoPlayNextEpisode
        if (pageMode != CatalogPageMode.VOD) {
            searchSnapshot = currentSnapshot()
            pageMode = CatalogPageMode.VOD
            applyPageMode()
            vodSnapshot?.let(::restoreBrowseSnapshot)
        }
        if (results.model.size > 0) results.requestFocusInWindow() else sources.requestFocusInWindow()
    }

    private fun applyPageMode() {
        val searching = pageMode == CatalogPageMode.SEARCH
        pageTitle.text = if (searching) "Search" else "Discover"
        detailBack.text = if (searching) "← Back to search results" else "← Back to catalogue"
        searchFields.isVisible = searching
        vodControls.isVisible = !searching
        filterBar.isVisible = !searching && filterBar.componentCount > 0
        rebuildSourceChoicesForMode()
        revalidate()
        repaint()
    }

    fun cancelAutoAdvance() {
        finishPlaybackStartup("Playback cancelled")
        playbackGeneration++
        autoPlayTimer.stop()
    }

    /** Returns within the VOD browser before the desktop shell handles Escape. */
    fun navigateBack(): Boolean {
        if (showingDetails) {
            showBrowsePane()
            results.requestFocusInWindow()
            return true
        }
        val previous = browseHistory.pop()
        if (previous != null) {
            restoreBrowseSnapshot(previous)
            return true
        }
        if (currentCategoryType != null) {
            loadHome()
            return true
        }
        return false
    }

    private fun refreshLocalGateway(selectedKey: String? = null, preserveInFlight: Boolean = false) {
        val gateway = localExecutors.values.takeIf(Collection<*>::isNotEmpty)?.let(::ExecutorCatalogGateway)
        if (preserveInFlight) catalog = gateway else replaceCatalog(gateway, "local executors refreshed")
        val displayed = CatalogSourceRoster.displayed(configuredSourceCapabilities.values.toList(), catalog?.sources().orEmpty())
        setSources(displayed, selectedKey)
        updateActions()
    }

    private fun setSources(available: List<CatalogSource>, selectedKey: String? = null) {
        updatingSources = true
        val choices = CatalogSourceChoices.from(available, includeShowAll = pageMode == CatalogPageMode.SEARCH)
        sources.model = DefaultComboBoxModel(choices.toTypedArray())
        val remembered = selectionStore.rememberedSelection()
        val preferredKey = selectedKey ?: selectionStore.sourceKey(remembered)
        sources.selectedIndex = CatalogSourceChoices.initialSelectionIndex(
            choices,
            preferredKey,
            selectionStore.isShowAll(remembered),
        )
        updatingSources = false
        logger.info(
            "source choices rebuilt real=${available.size} showAll=${choices.firstOrNull()?.isShowAll == true} " +
                "firstProvider=${available.firstOrNull()?.key ?: "none"} selectedIndex=${sources.selectedIndex} " +
                "selection=${if (sources.selectedIndex == 0) "show-all" else "provider"}",
        )
    }

    private fun rebuildSourceChoicesForMode() {
        if (updatingSources || sources.itemCount == 0) return
        val selected = (sources.selectedItem as? CatalogSourceChoice)?.source?.key
        setSources(CatalogSourceRoster.displayed(configuredSourceCapabilities.values.toList(), catalog?.sources().orEmpty()), selected)
    }

    private fun rememberSourceSelection() {
        val choice = sources.selectedItem as? CatalogSourceChoice ?: return
        choice.source?.key?.let(selectionStore::rememberSource) ?: selectionStore.rememberShowAll()
        logger.info(
            "source selection remembered selection=${if (choice.isShowAll) "show-all" else "provider"} " +
                "key=${choice.source?.key ?: "none"}",
        )
    }

    private fun closeExecutor(executor: CatalogSourceExecutor) {
        runCatching { executor.cancel() }
        (executor as? AutoCloseable)?.let { runCatching { it.close() } }
    }

    private fun retireLocalExecutors() {
        val retired = localExecutors.values.toList()
        localExecutors.clear()
        if (retired.isNotEmpty()) {
            logger.info("executor retirement queued count=${retired.size}")
            retired.forEach(::retireExecutor)
        }
    }

    private fun retireExecutor(executor: CatalogSourceExecutor) {
        // A source can finish after the panel is closed; the cleanup pool is already shut down
        // then, and a rejected task would surface on the EDT instead of the worker.
        runCatching {
            executorCleanup.execute {
                val started = System.nanoTime()
                closeExecutor(executor)
                logger.info(
                    "executor retirement complete key=${executor.source.key} " +
                        "elapsedMs=${(System.nanoTime() - started) / 1_000_000}",
                )
            }
        }.onFailure { if (it !is RejectedExecutionException) throw it }
    }

    override fun close() {
        if (closed) return
        closed = true
        playbackGeneration++
        playbackStartupTimer.stop()
        finishActiveViewing(completed = false)
        automaticHomeLoad.stop()
        autoPlayTimer.stop()
        suggestionTimer.stop()
        searchWordClient.close()
        auditWorker?.cancel(true)
        thumbnails.close()
        retireLocalExecutors()
        jsProxyServer?.close()
        jsProxyServer = null
        executorCleanup.shutdown()
        sourceCoordinator.close()
        sourceOperations.shutdownNow()
    }

    private fun loadHome() {
        if (pageMode != CatalogPageMode.VOD) return
        automaticHomeLoad.stop()
        browseHistory.clear()
        thumbnails.retryFailed()
        if (showUnavailableSelection()) return
        val gateway = catalog ?: return
        val generation = catalogGeneration
        val available = realSources()
        val selectedTargets = CatalogHomeSourceOrder.prioritize(
            selectedCatalogTargets(available),
        )
        val targets = selectedTargets.take(1)
        if (targets.isEmpty()) return
        val scope = targets.single().name
        clearCategoryBrowsing(clearTypes = true)
        val request = ++browseGeneration
        val homeRequest = ++homeLoadGeneration
        renderedHomeRequest = null
        if (targets.size == 1) {
            val source = targets.single()
            run("Loading VOD from $scope…", {
                val home = gateway.browseHome(source.key)
                if (source.indexProvider && home.items.size <= 1 && home.types.isNotEmpty()) {
                    val firstType = home.types.first()
                    val firstPage = gateway.category(source.key, firstType.id, 1, emptyMap())
                    if (firstPage.items.isNotEmpty()) home.copy(items = firstPage.items) else home
                } else {
                    home
                }
            }, generation) { response ->
                if (request != browseGeneration) return@run
                showItems(response.items, source.key)
                setBrowseTypes(CatalogTypeChoices.from(source, response.types, qualifySource = false))
                if (response.items.isEmpty() && response.types.isNotEmpty()) {
                    types.selectedIndex = 0
                } else {
                    status.text = if (response.items.isEmpty()) {
                        if (source.searchable) "${source.name} returned no home catalogue. Try searching for a title; this source may be search-only or temporarily unavailable."
                        else "${source.name} returned no home catalogue or categories. Retry later or check the source in Settings."
                    } else {
                        "Loaded ${response.items.size} VOD items and ${response.types.size} media types."
                    }
                }
            }
            return
        }
        run("Loading VOD from $scope…", {
            collectHomeSources(targets, request, homeRequest) { source -> gateway.browseHome(source.key) }
        }, generation) { outcome ->
            if (request != browseGeneration) return@run
            showItems(outcome.items)
            setBrowseTypes(outcome.types)
            renderedHomeRequest = request
            status.text = buildString {
                append(if (outcome.items.isEmpty()) "No home VOD returned" else "Loaded ${outcome.items.size} VOD items")
                if (outcome.types.isNotEmpty()) append(" and ${outcome.types.size} site-specific media types")
                appendFailureSummary(outcome.failures)
                append('.')
            }
            outcome.failures.forEach { (source, failure) ->
                logSourceFailure("home", source, failure)
            }
        }
    }

    private fun search(rememberCurrent: Boolean = true) {
        if (showUnavailableSelection()) return
        val companion = catalog ?: return
        val generation = catalogGeneration
        val available = realSources()
        val selectedTargets = selectedCatalogTargets(available)
        val showAll = (sources.selectedItem as? CatalogSourceChoice)?.isShowAll == true
        val targets = selectedTargets.filter(CatalogSource::searchable)
        if (targets.isEmpty()) {
            error("This discovery source is not searchable; select Search All or another provider.")
            return
        }
        val text = query.text.trim()
        if (text.isEmpty()) { error("Enter a search title."); return }
        rebuildRecentKeywords(searchHistoryStore.add(text))
        automaticHomeLoad.stop()
        showBrowsePane()
        if (rememberCurrent) pushBrowseHistory()
        clearCategoryBrowsing(clearTypes = false)
        val request = ++browseGeneration
        val scope = if (showAll) "all sites" else targets.single().name
        run("Searching $scope…", {
            collectSources(targets, "search") { source -> companion.search(source.key, text) }.also {
                if (it.failures.size == targets.size) throw it.failures.first().second
            }
        }, generation) { outcome ->
            if (request != browseGeneration) return@run
            val found = outcome.items
            showItems(found)
            status.text = buildString {
                append("Found ${found.size} results")
                if (showAll) appendSourceCoverage(outcome.returnedSources, targets.size, "results")
                appendFailureSummary(outcome.failures)
                append('.')
            }
            outcome.failures.forEach { (source, failure) ->
                logSourceFailure("search", source, failure)
            }
            updateActions()
        }
    }

    private fun selectedCatalogTargets(available: List<CatalogSource>): List<CatalogSource> {
        val choice = sources.selectedItem as? CatalogSourceChoice
        return CatalogSourceRoster.targets(available, choice?.source?.key)
    }

    private fun showItems(found: List<CatalogItem>, recommendationSourceKey: String? = null, page: Int = 0) {
        historyActions.isVisible = false
        val displayed = recommendationSourceKey?.let { sourceKey ->
            val media = found.filter { it.kind == CatalogItemKind.MEDIA }
            if (media.size < 2) found else {
                val ranked = userLibrary.recommendations().rank(sourceKey, media).iterator()
                found.map { item -> if (item.kind == CatalogItemKind.MEDIA) ranked.next() else item }
            }
        } ?: found
        allItems = displayed
        thumbnailPage = page
        renderThumbnailPage()
    }

    private fun renderThumbnailPage(selectedIndex: Int = 0) {
        val size = (thumbnailsPerPage.value as Number).toInt()
        val category = currentCategoryPage?.takeIf { currentCategoryType != null }
        val loadedPages = if (allItems.isEmpty()) 1 else CatalogPagination.pageCount(allItems.size, size)
        val count = category?.pageCount?.takeIf { it > 0 } ?: loadedPages
        thumbnailPage = thumbnailPage.coerceIn(0, (loadedPages - 1).coerceAtLeast(0))
        items = allItems
        val displayPage = thumbnailPage + 1
        val first = if (items.isEmpty()) 0 else thumbnailPage * size + 1
        val last = if (items.isEmpty()) 0 else minOf((thumbnailPage + 1) * size, items.size)
        val total = if (category != null) {
            if (category.pageCount > 0) category.total.toString() else "?"
        } else allItems.size.toString()
        thumbnailPageLabel.text = "$first–$last of $total · Page $displayPage / ${if (count == 0) "?" else count}"
        thumbnailPageLabel.toolTipText = if (category != null) {
            "Scroll continuously; the next source page loads at the end of the list."
        } else {
            "Scroll continuously or use Previous and Next to jump by a page."
        }
        thumbnailPrevious.isEnabled = thumbnailPage > 0
        thumbnailNext.isEnabled = when {
            category != null -> thumbnailPage + 1 < loadedPages || category.hasNext
            else -> thumbnailPage + 1 < loadedPages
        }
        thumbnailPrevious.isVisible = items.isNotEmpty()
        thumbnailNext.isVisible = items.isNotEmpty()
        updateStatusPresentation()
        detail = null
        clearDetails()
        model(results).apply {
            clear()
            items.forEach(::addElement)
        }
        thumbnails.prefetch(items) { results.repaint() }
        results.selectedIndex = if (items.isEmpty()) -1 else selectedIndex.coerceIn(0, items.lastIndex)
        if (items.isNotEmpty() && selectedIndex > 0) results.ensureIndexIsVisible(results.selectedIndex)
        updateActions()
    }

    private fun navigateToThumbnailPage(requestedPage: Int) {
        val loadedPages = CatalogPagination.pageCount(allItems.size, (thumbnailsPerPage.value as Number).toInt())
        if (requestedPage < loadedPages) {
            scrollToThumbnailPage(requestedPage)
            return
        }
        val category = currentCategoryPage?.takeIf { currentCategoryType != null } ?: return
        if (category.hasNext && !categoryLoading) loadCategory(category.page + 1, revealPage = true)
    }

    private fun scrollToThumbnailPage(requestedPage: Int) {
        if (allItems.isEmpty()) return
        val size = (thumbnailsPerPage.value as Number).toInt()
        val targetPage = requestedPage.coerceIn(0, CatalogPagination.pageCount(allItems.size, size) - 1)
        thumbnailPage = targetPage
        val index = (targetPage * size).coerceAtMost(allItems.lastIndex)
        javax.swing.SwingUtilities.invokeLater {
            results.getCellBounds(index, index)?.let { bounds ->
                resultsScroll.viewport.viewPosition = java.awt.Point(0, bounds.y)
            }
            renderPagePosition()
        }
    }

    private fun updateVisibleThumbnailPage(loadMoreAtEnd: Boolean) {
        if (allItems.isEmpty()) return
        val first = results.firstVisibleIndex.takeIf { it >= 0 } ?: return
        val nextPage = first / (thumbnailsPerPage.value as Number).toInt()
        if (nextPage != thumbnailPage) {
            thumbnailPage = nextPage
            renderPagePosition()
        }
        val bar = resultsScroll.verticalScrollBar
        val atEnd = bar.value + bar.visibleAmount >= bar.maximum - bar.unitIncrement
        val category = currentCategoryPage?.takeIf { currentCategoryType != null }
        if (loadMoreAtEnd && resultsScroll.isShowing && atEnd && category?.hasNext == true && !categoryLoading) {
            loadCategory(category.page + 1, revealPage = false)
        }
    }

    private fun renderPagePosition() {
        val size = (thumbnailsPerPage.value as Number).toInt()
        val category = currentCategoryPage?.takeIf { currentCategoryType != null }
        val loadedPages = CatalogPagination.pageCount(allItems.size, size)
        val count = category?.pageCount?.takeIf { it > 0 } ?: loadedPages
        val first = if (allItems.isEmpty()) 0 else thumbnailPage * size + 1
        val last = if (allItems.isEmpty()) 0 else minOf((thumbnailPage + 1) * size, allItems.size)
        val total = category?.let { if (it.pageCount > 0) it.total.toString() else "?" } ?: allItems.size.toString()
        thumbnailPageLabel.text = "$first–$last of $total · Page ${thumbnailPage + 1} / $count"
        thumbnailPrevious.isEnabled = thumbnailPage > 0
        thumbnailNext.isEnabled = thumbnailPage + 1 < loadedPages || category?.hasNext == true
        updateCategoryActions()
    }

    private fun setBrowseTypes(found: List<CatalogTypeChoice>) {
        val ordered = CatalogTypeChoices.homeFirst(found)
        updatingBrowseControls = true
        types.model = DefaultComboBoxModel(ordered.toTypedArray())
        types.selectedIndex = -1
        updatingBrowseControls = false
        logger.info(
            "media type choices rebuilt count=${ordered.size} " +
                "sources=${ordered.map(CatalogTypeChoice::sourceKey).distinct().size} " +
                "indexTypes=${ordered.count(CatalogTypeChoice::indexProvider)}",
        )
        rebuildTypeBar()
        updateCategoryActions()
    }

    private fun mergeBrowseTypes(found: List<CatalogTypeChoice>, expectedRequest: Long? = null) {
        if (expectedRequest != null && expectedRequest != browseGeneration) return
        if (sources.selectedIndex != 0 || found.isEmpty()) return
        val selected = types.selectedItem as? CatalogTypeChoice
        val existing = (0 until types.itemCount).map(types::getItemAt)
        val combined = CatalogTypeChoices.homeFirst(
            (existing + found).distinctBy { it.sourceKey to it.type.id },
        )
        updatingBrowseControls = true
        types.model = DefaultComboBoxModel(combined.toTypedArray())
        types.selectedIndex = selected?.let { current ->
            combined.indexOfFirst { it.sourceKey == current.sourceKey && it.type.id == current.type.id }
        } ?: -1
        updatingBrowseControls = false
        logger.info(
            "late media types merged added=${combined.size - existing.size} total=${combined.size} " +
                "sources=${combined.map(CatalogTypeChoice::sourceKey).distinct().size} " +
                "indexTypes=${combined.count(CatalogTypeChoice::indexProvider)}",
        )
        if (currentCategoryType == null) {
            status.text = "Media types updated: ${combined.size} across " +
                "${combined.map(CatalogTypeChoice::sourceKey).distinct().size} sites."
        }
        rebuildTypeBar()
        updateCategoryActions()
    }

    private fun mergeProgressiveHome(
        outcome: CatalogSourceOutcome,
        expectedRequest: Long,
        expectedHomeRequest: Long,
    ) {
        if (expectedHomeRequest != homeLoadGeneration || sources.selectedIndex != 0 || outcome.failure != null) return
        // Skip types from sources that returned 0 items (e.g. 虎牙js, 斗鱼js)
        if (outcome.payload.items.isEmpty()) {
            logger.info("late home types ignored key=${outcome.source.key} items=0")
            return
        }
        val choices = CatalogTypeChoices.from(outcome.source, outcome.payload.types, qualifySource = true)
        if (expectedRequest != browseGeneration) {
            mergeBrowseTypes(choices)
            logger.info(
                "late home cards ignored during active browse key=${outcome.source.key} " +
                    "items=${outcome.payload.items.size} typesMerged=${choices.size}",
            )
            return
        }
        if (renderedHomeRequest != expectedRequest) {
            Timer(PROGRESSIVE_HOME_MERGE_RETRY_MILLIS) {
                mergeProgressiveHome(outcome, expectedRequest, expectedHomeRequest)
            }.apply {
                isRepeats = false
                start()
            }
            return
        }

        val knownItems = allItems.mapTo(mutableSetOf()) { it.sourceKey to it.id }
        val addedItems = outcome.payload.items.filter { knownItems.add(it.sourceKey to it.id) }
        if (addedItems.isNotEmpty()) {
            allItems = allItems + addedItems
            renderThumbnailPage(results.selectedIndex)
        }

        mergeBrowseTypes(choices, expectedRequest)
        if (addedItems.isNotEmpty() || choices.isNotEmpty()) {
            val sourceCount = (0 until types.itemCount).map(types::getItemAt)
                .map(CatalogTypeChoice::sourceKey)
                .distinct()
                .size
            status.text = "Home updated: ${items.size} VOD items and ${types.itemCount} media types" +
                if (sourceCount > 0) " across $sourceCount sites." else "."
            logger.info(
                "late home merged key=${outcome.source.key} addedItems=${addedItems.size} " +
                    "types=${choices.size} totalItems=${items.size} totalTypes=${types.itemCount}",
            )
            updateActions()
        }
    }

    private fun clearCategoryBrowsing(clearTypes: Boolean) {
        categoryPagerScope = null
        browseGeneration++
        updatingBrowseControls = true
        currentCategoryType = null
        currentCategorySourceKey = null
        currentCategoryId = null
        currentCategoryPage = null
        categoryLoading = false
        categorySelections.clear()
        categoryStack.clear()
        filterBar.removeAll()
        filterBar.isVisible = false
        if (clearTypes) {
            types.model = DefaultComboBoxModel()
            filterSelections.clear()
            visibleFilterScopes.clear()
        }
        else types.selectedIndex = -1
        updatingBrowseControls = false
        filterBar.revalidate()
        filterBar.repaint()
        rebuildTypeBar()
        updateCategoryActions()
    }

    private fun selectCategoryType(choice: CatalogTypeChoice?) {
        if (choice == null) return
        val type = choice.type
        currentCategoryType = type
        currentCategorySourceKey = choice.sourceKey
        currentCategoryId = type.id
        currentCategoryPage = null
        categoryStack.clear()
        categorySelections.clear()
        val restored = filterSelections.selection(choice.sourceKey, type)
        categorySelections.putAll(restored)
        rebuildFilters()
        rebuildTypeBar()
        loadCategory(1)
    }

    /** Mirrors Leanback's horizontal type adapter; all entries come from provider homeContent. */
    private fun rebuildTypeBar() {
        typeBar.removeAll()
        val selectedSource = currentCategorySourceKey
        val selectedType = currentCategoryType?.id
        (0 until types.itemCount).map(types::getItemAt).forEach { choice ->
            typeBar.add(LeanbackChip(choice.name).apply {
                isSelected = choice.sourceKey == selectedSource && choice.type.id == selectedType
                toolTipText = if (choice.type.filters.isEmpty()) choice.name else {
                    val visible = (choice.sourceKey to choice.type.id) in visibleFilterScopes
                    "${choice.name} · click to ${if (visible) "hide" else "show"} filters"
                }
                addActionListener {
                    if (!updatingBrowseControls &&
                        repeatRequests.accept("type-chip:${choice.sourceKey}:${choice.type.id}")) {
                        activateTypeChoice(choice)
                    }
                }
            })
        }
        typeBar.revalidate()
        typeBar.repaint()
        if (currentCategoryType == null) {
            javax.swing.SwingUtilities.invokeLater {
                typeScroll.horizontalScrollBar.value = 0
                logger.info(
                    "type rail rendered buttons=${typeBar.componentCount} " +
                        "visible=${typeScroll.isShowing} size=${typeScroll.width}x${typeScroll.height}",
                )
            }
        }
    }

    /** Leanback selects the type and toggles that type's filter rows with the same click. */
    private fun activateTypeChoice(choice: CatalogTypeChoice) {
        pushBrowseHistory()
        val scope = choice.sourceKey to choice.type.id
        if (choice.type.filters.isNotEmpty()) {
            if (!visibleFilterScopes.add(scope)) visibleFilterScopes.remove(scope)
        }
        val alreadySelected = currentCategorySourceKey == choice.sourceKey && currentCategoryType?.id == choice.type.id
        if (alreadySelected) {
            rebuildFilters()
            rebuildTypeBar()
        } else {
            types.selectedItem = choice
        }
    }

    private fun rebuildFilters() {
        val type = currentCategoryType
        updatingBrowseControls = true
        filterBar.removeAll()
        val visible = type != null && currentCategorySourceKey?.let { it to type.id } in visibleFilterScopes
        if (type != null && visible) {
            CatalogFilterSelectionPolicy.rows(type, categorySelections).forEach { row ->
                val values = JPanel(FlowLayout(FlowLayout.LEFT, 4, 1)).apply {
                    row.values.forEach { value ->
                        add(LeanbackChip(value.name, compact = true).apply {
                            isSelected = row.selectedValue == value.value
                            toolTipText = if (isSelected) "Click again to clear ${row.name}" else "Filter ${row.name}"
                            addActionListener {
                                val typeId = currentCategoryType?.id
                                val sourceKey = currentCategorySourceKey
                                if (!updatingBrowseControls &&
                                    repeatRequests.accept("filter:$sourceKey:$typeId:${row.key}:${value.value}")) {
                                    updateFilter(row.key, value.value)
                                }
                            }
                        })
                    }
                }
                filterBar.add(
                    JScrollPane(
                        values,
                        JScrollPane.VERTICAL_SCROLLBAR_NEVER,
                        JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED,
                    ).apply {
                        border = BorderFactory.createEmptyBorder()
                        preferredSize = Dimension(100, FILTER_ROW_HEIGHT)
                        horizontalScrollBar.unitIncrement = 24
                    },
                )
            }
        }
        filterBar.isVisible = pageMode == CatalogPageMode.VOD && filterBar.componentCount > 0
        updatingBrowseControls = false
        filterBar.revalidate()
        filterBar.repaint()
        updateCategoryActions()
    }

    private fun updateFilter(key: String, value: String) {
        val type = currentCategoryType ?: return
        val sourceKey = currentCategorySourceKey ?: return
        pushBrowseHistory()
        filterSelections.remember(sourceKey, type, categorySelections)
        val selected = filterSelections.toggle(sourceKey, type, key, value)
        categorySelections.clear()
        categorySelections.putAll(selected)
        rememberFilterSelection(type)
        categoryStack.clear()
        rebuildFilters()
        loadCategory(1)
    }

    private fun rememberFilterSelection(type: CatalogType) {
        val sourceKey = currentCategorySourceKey ?: return
        filterSelections.remember(sourceKey, type, categorySelections)
    }

    private fun loadCategory(page: Int, revealPage: Boolean = true) {
        val gateway = catalog ?: return
        val sourceKey = currentCategorySourceKey ?: return
        val source = realSources().firstOrNull { it.key == sourceKey } ?: run {
            error("The site for this media type is no longer enabled.")
            return
        }
        val type = currentCategoryType ?: return
        val typeId = currentCategoryId ?: return
        val generation = catalogGeneration
        val request = ++browseGeneration
        val requestedPage = page.coerceAtLeast(1)
        val selected = CatalogFilterSelectionPolicy.normalize(type, categorySelections)
        val scope = Triple(source.key, typeId, selected)
        if (categoryPagerScope != scope || categoryPagerGeneration != generation) {
            categoryPager = CatalogResultPager()
            categoryPagerScope = scope
            categoryPagerGeneration = generation
        }
        val pager = categoryPager
        val pageSize = (thumbnailsPerPage.value as Number).toInt()
        categoryLoading = true
        run("Loading ${type.name}, page $requestedPage…", {
            pager.loadThrough(requestedPage, pageSize) { providerPage ->
                gateway.category(source.key, typeId, providerPage, selected)
            }
        }, generation, completion = {
            categoryLoading = false
        }) { response ->
            if (request != browseGeneration) return@run
            val previousViewPosition = resultsScroll.viewport.viewPosition
            val previousVisiblePage = thumbnailPage
            currentCategoryPage = response
            showItems(
                response.items,
                source.key,
                page = if (revealPage) response.page - 1 else previousVisiblePage,
            )
            if (revealPage) {
                scrollToThumbnailPage(response.page - 1)
            } else {
                javax.swing.SwingUtilities.invokeLater {
                    resultsScroll.viewport.viewPosition = previousViewPosition
                    updateVisibleThumbnailPage(loadMoreAtEnd = true)
                }
            }
            updateCategoryActions()
            val pageText = if (response.pageCount == 0) "${response.page}" else "${response.page}/${response.pageCount}"
            status.text = buildString {
                append("Loaded ${response.items.size} items from ${type.name}, page $pageText")
                if (selected.isNotEmpty()) append(" with ${selected.size} filters")
                append('.')
            }
            logger.info(
                "category loaded source=${source.key} type=$typeId page=${response.page} " +
                    "pageCount=${response.pageCount} total=${response.total} filters=$selected items=${response.items.size}",
            )
        }
    }

    private fun openFolder(item: CatalogItem) {
        val type = currentCategoryType ?: run {
            error("This folder requires a provider media type; return Home and choose a Type.")
            return
        }
        pushBrowseHistory()
        currentCategoryId?.let { id ->
            categoryStack += CategoryState(
                type,
                currentCategorySourceKey.orEmpty(),
                id,
                categorySelections.toMap(),
                currentCategoryPage?.page ?: 1,
            )
        }
        currentCategoryId = item.id
        currentCategoryPage = null
        rebuildFilters()
        loadCategory(1)
    }

    private fun updateCategoryActions() {
        categoryBack.isEnabled = browseHistory.isNotEmpty() || currentCategoryType != null || showingDetails
        categoryBack.isVisible = categoryBack.isEnabled
        types.isEnabled = types.itemCount > 0
    }

    private fun scheduleAutomaticHomeLoad() {
        automaticHomeLoad.restart()
        status.text = "Sources changed; waiting briefly for desktop providers to settle…"
    }

    private fun collectSources(
        targets: List<CatalogSource>,
        operationName: String,
        operation: (CatalogSource) -> List<CatalogItem>,
    ): CatalogSearchOutcome = collectSourcePayloads(targets, operationName) { source ->
        CatalogSourcePayload(operation(source))
    }

    private fun collectHomeSources(
        targets: List<CatalogSource>,
        expectedRequest: Long,
        expectedHomeRequest: Long,
        operation: (CatalogSource) -> CatalogHome,
    ): CatalogSearchOutcome = collectSourcePayloads(
        targets,
        "home",
        operation = { source -> operation(source).let { CatalogSourcePayload(it.items, it.types) } },
        lateResult = { outcome ->
            if (outcome.failure == null &&
                (outcome.payload.items.isNotEmpty() || outcome.payload.types.isNotEmpty())
            ) {
                javax.swing.SwingUtilities.invokeLater {
                    mergeProgressiveHome(outcome, expectedRequest, expectedHomeRequest)
                }
            }
        },
    )

    private fun collectSourcePayloads(
        targets: List<CatalogSource>,
        operationName: String,
        lateResult: ((CatalogSourceOutcome) -> Unit)? = null,
        operation: (CatalogSource) -> CatalogSourcePayload,
    ): CatalogSearchOutcome {
        if (closed) return CatalogSearchOutcome(emptyList(), emptyList(), emptyList(), 0)
        val outcomes = sourceCoordinator.collect(targets, operationName, lateResult, operation)
        val typeChoices = CatalogTypeChoices.homeFirst(outcomes
            .filter { it.payload.items.isNotEmpty() }
            .flatMap { outcome ->
                CatalogTypeChoices.from(outcome.source, outcome.payload.types, qualifySource = true)
            }.distinctBy { it.sourceKey to it.type.id })
        return CatalogSearchOutcome(
            items = outcomes.flatMap { it.payload.items }.distinctBy { it.sourceKey to it.id },
            types = typeChoices,
            failures = outcomes.mapNotNull { outcome -> outcome.failure?.let { outcome.source to it } },
            returnedSources = outcomes.count { it.payload.items.isNotEmpty() },
        )
    }

    private fun StringBuilder.appendFailureSummary(failures: List<Pair<CatalogSource, Throwable>>) {
        val loading = failures.count { it.second is TimeoutException && it.second.message?.contains("still loading") == true }
        val timedOut = failures.count { it.second is TimeoutException } - loading
        val failed = failures.count { it.second !is TimeoutException }
        if (loading > 0) append("; $loading sites still loading home data")
        if (timedOut > 0) append("; stopped waiting for $timedOut slow sites")
        if (failed > 0) append("; $failed sites failed")
    }

    private fun StringBuilder.appendSourceCoverage(returned: Int, enabled: Int, noun: String) {
        append("; $returned of $enabled desktop-enabled sites returned $noun")
        if (configuredSiteCount > 0) {
            append(" ($configuredSearchableSiteCount searchable of $configuredSiteCount configured)")
        }
    }

    private fun logSourceFailure(operation: String, source: CatalogSource, failure: Throwable) {
        val message = "$operation source=${source.key} error=${rootMessage(failure)}"
        if (failure is TimeoutException) logger.info(message) else logger.log(Level.WARNING, message, failure)
    }

    private fun activateSelection() {
        val item = items.getOrNull(results.selectedIndex) ?: return
        when (item.kind) {
            CatalogItemKind.MEDIA -> if (source(item.sourceKey)?.indexProvider == true) {
                searchDiscoveryItem(item)
            } else {
                loadDetail()
            }
            CatalogItemKind.FOLDER -> openFolder(item)
            CatalogItemKind.ACTION -> {
                status.text = "${item.name} is a provider action, not playable media; desktop action support is not available yet."
            }
        }
    }

    /** Android opens CollectActivity for index-provider cards; desktop performs the same all-site search. */
    private fun searchDiscoveryItem(item: CatalogItem) {
        val title = item.name.trim()
        if (title.isEmpty()) {
            error("This discovery card has no title to search.")
            return
        }
        val searchable = realSources().count(CatalogSource::searchable)
        if (searchable == 0) {
            error("Enable at least one searchable provider before opening discovery cards.")
            return
        }
        focusSearch()
        sources.selectedIndex = (0 until sources.itemCount).firstOrNull { sources.getItemAt(it).isShowAll } ?: 0
        query.text = title
        logger.info("index discovery source=${item.sourceKey} title=$title searchableTargets=$searchable")
        search(rememberCurrent = false)
    }

    private fun loadDetail() {
        val companion = catalog ?: return
        val generation = catalogGeneration
        val item = items.getOrNull(results.selectedIndex) ?: return
        if (item.kind != CatalogItemKind.MEDIA) {
            activateSelection()
            return
        }
        val savedEntry = userLibrary.find(item)
        run("Loading details…", {
            LibraryDetailResolver.resolve(companion, item, savedEntry != null)
        }, generation) { response ->
            val value = response.withSummaryFallback(item)
            detail = value
            showDetailPoster(value.item.name, value.item.picture)
            details.text = buildString {
                appendLine(value.item.name)
                appendLine(sourceName(value.item.sourceKey))
                value.item.year.takeIf(String::isNotBlank)?.let { appendLine("Year: $it") }
                value.area.takeIf(String::isNotBlank)?.let { appendLine("Area: $it") }
                value.actor.takeIf(String::isNotBlank)?.let { appendLine("Cast: $it") }
                value.director.takeIf(String::isNotBlank)?.let { appendLine("Director: $it") }
                if (value.content.isNotBlank()) append("\n${value.content}")
            }
            lines.model = DefaultComboBoxModel(value.lines.toTypedArray())
            refreshEpisodes()
            restoreEpisodeSelection(value.item, savedEntry)
            showDetailPane()
            status.text = "Loaded ${value.lines.sumOf { it.episodes.size }} episodes."
            if (playbackSettings.autoPlayFirstEpisode) {
                autoPlayTimer.restart()
            }
        }
    }

    private fun refreshEpisodes() {
        val line = lines.selectedItem as? CatalogLine
        model(episodes).apply {
            clear()
            line?.episodes?.forEach { addElement(it.name) }
        }
        episodes.selectedIndex = if (line?.episodes.isNullOrEmpty()) -1 else 0
        updateActions()
    }

    private fun playEpisode(reuseRunningPlayer: Boolean = false) {
        val companion = catalog ?: return
        val generation = catalogGeneration
        val playback = ++playbackGeneration
        val item = detail?.item ?: return
        val line = lines.selectedItem as? CatalogLine ?: return
        val episodeIndex = episodes.selectedIndex
        val episode = line.episodes.getOrNull(episodeIndex) ?: return
        resolving = true
        playbackStartupNanos = System.nanoTime()
        playbackStartupStep = 1
        playbackStartupStage = "Resolving video"
        playbackStartup.isVisible = true
        playbackStartupTimer.start()
        refreshPlaybackStartup()
        showProgress(true)
        updateActions()
        run("Resolving ${episode.name}…", {
            val prewarmed = prewarmedUrl
            if (prewarmed != null && prewarmedPlayback == playback) {
                prewarmedUrl = null
                CatalogPlayback(supported = true, url = prewarmed, headers = prewarmedHeaders)
            } else {
                companion.play(item.sourceKey, line.flag, episode.id)
            }
        }, generation, expectedPlayback = playback) { resolved ->
            if (!resolved.supported) {
                logger.warning("playback resolution rejected source=${item.sourceKey} reason=${resolved.message}")
                status.text = resolved.message.ifBlank { "This episode is not supported on desktop." }
                finishPlaybackStartup("Playback unavailable")
                updateActions()
            } else {
                finishActiveViewing(completed = false)
                val viewing = ActiveViewing(item, detail, userLibrary.privacy.beginSession())
                activeViewing = viewing
                val savedPosition = if (playbackSettings.restorePlaybackPosition) {
                    userLibrary.playbackPosition(item, line, episode)
                } else 0L
                playbackStartupStep = 2
                playbackStartupStage = "Preparing and opening ${player?.name ?: "player"}"
                refreshPlaybackStartup()
                run(playbackStartupStage + "…", {
                    player?.play(
                        resolved.url,
                        resolved.headers,
                        onEnded = {
                            javax.swing.SwingUtilities.invokeLater {
                                finishViewing(viewing, completed = true)
                                autoAdvance(playback, item, line, episodeIndex)
                            }
                        },
                        onClosed = {
                            javax.swing.SwingUtilities.invokeLater {
                                if (closed || playback != playbackGeneration) return@invokeLater
                                finishViewing(viewing, completed = false)
                                finishPlaybackStartup("Player closed before playback started")
                                updateActions()
                            }
                        },
                        reuseRunningPlayer = reuseRunningPlayer,
                        initialSpeed = playbackSettings.defaultSpeed,
                        fullscreen = playbackSettings.fullscreenOnStart,
                        isCancelled = { closed || playback != playbackGeneration || generation != catalogGeneration },
                        onPlaybackFailed = {
                            javax.swing.SwingUtilities.invokeLater {
                                if (!closed && playback == playbackGeneration) {
                                    finishPlaybackStartup("Playback failed")
                                    status.text = "mpv could not open this video. Retry or choose another line."
                                    finishViewing(viewing, completed = false)
                                    updateActions()
                                }
                            }
                        },
                        onPlaybackStarted = {
                            javax.swing.SwingUtilities.invokeLater {
                                if (!closed && playback == playbackGeneration) {
                                    if (savedPosition > 5000) player?.seekTo(savedPosition / 1000.0)
                                    finishPlaybackStartup("Playback started")
                                    updateActions()
                                }
                            }
                        },
                        displayName = listOf(item.name, episode.name)
                            .filter(String::isNotBlank)
                            .joinToString(" — "),
                    )
                }, generation, expectedPlayback = playback) { result ->
                    status.text = result?.message ?: "Install mpv or VLC and restart TVBox Desktop."
                    if (result?.started == true) {
                        if (player?.name?.equals("mpv", true) == true && player.isRunning()) {
                            if (playbackStartupTimer.isRunning) {
                                playbackStartupStep = 3
                                playbackStartupStage = "Waiting for mpv playback"
                                refreshPlaybackStartup()
                            }
                        } else finishPlaybackStartup("Player opened")
                        userLibrary.recordPlayback(item, line, episode, viewing.privacySession)
                        if (userLibrary.privacy.mayRecord(viewing.privacySession)) logger.info(
                            "history saved source=${item.sourceKey} item=${item.id} " +
                                "line=${line.flag} episode=${episode.id}",
                        )
                        player?.observePlaybackState(PlaybackStateCallback { state ->
                            javax.swing.SwingUtilities.invokeLater {
                                if (!closed && playback == playbackGeneration) updatePlaybackState(state)
                            }
                        })
                        updateActions()
                    } else {
                        finishPlaybackStartup("Playback failed")
                        if (activeViewing === viewing) activeViewing = null
                        updateActions()
                    }
                }
            }
        }
    }

    private fun refreshPlaybackStartup() {
        val seconds = ((System.nanoTime() - playbackStartupNanos) / 1_000_000_000).coerceAtLeast(0)
        progressBar.value = playbackStartupStep - 1
        playbackStartup.text = "$playbackStartupStep/3 · $playbackStartupStage · ${seconds}s" +
            if (seconds >= 20) " · Still waiting" else ""
        playbackStartup.toolTipText = "${playbackStartup.text}. Elapsed time, not an estimate. Stop cancels startup."
    }

    private fun finishPlaybackStartup(message: String) {
        if (playbackStartupTimer.isRunning) {
            val seconds = ((System.nanoTime() - playbackStartupNanos) / 1_000_000_000).coerceAtLeast(0)
            playbackStartupTimer.stop()
            playbackStartup.text = "$message · ${seconds}s elapsed"
            playbackStartup.toolTipText = playbackStartup.text
            if (message == "Playback started") progressBar.value = progressBar.maximum
        }
        resolving = false
        showProgress(false)
    }

    private fun showProgress(show: Boolean) {
        progressBar.isVisible = show
        progressBar.isIndeterminate = false
    }

    private fun updatePlaybackState(state: PlaybackState) {
        if (state.positionSeconds > 0 && !state.buffering && playbackStartupTimer.isRunning) {
            finishPlaybackStartup("Playback started")
            updateActions()
        }
        speedOptions.indexOfFirst { it == state.speed }.takeIf { it >= 0 }?.let { speedIndex = it }
        speedButton.text = "${state.speed}x"
        playerPause.text = if (state.paused) "▶ Play" else "❚❚ Pause"
        if (state.buffering) {
            status.text = "Buffering…"
        } else if (state.positionSeconds > 0 && state.durationSeconds > 0) {
            val pos = formatTime(state.positionSeconds)
            val dur = formatTime(state.durationSeconds)
            status.text = "Playing $pos / $dur${if (state.speed != 1.0) " · ${state.speed}x" else ""}"
        }
        activeViewing?.let { viewing ->
            viewing.watchTime.observe(state, System.nanoTime() / 1_000_000L)
            viewing.lastState = state
            val now = System.nanoTime() / 1_000_000L
            if (state.durationSeconds > 0 && com.wuming.tv.core.library.HistoryPolicy.canSync(now, viewing.lastSavedAtMillis)) {
                saveViewingPosition(viewing)
                viewing.lastSavedAtMillis = now
            }
        }
    }

    private fun formatTime(seconds: Double): String {
        val total = seconds.toInt()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun replayEpisode() {
        val line = lines.selectedItem as? CatalogLine ?: return
        val episodeIndex = episodes.selectedIndex
        if (episodeIndex < 0) return
        playEpisode(reuseRunningPlayer = true)
    }

    private fun cycleSpeed() {
        speedIndex = (speedIndex + 1) % speedOptions.size
        val speed = speedOptions[speedIndex]
        speedButton.text = "${speed}x"
        playbackSettings.defaultSpeed = speed
        player?.setSpeed(speed)
        status.text = "Speed: ${speed}x"
    }

    private fun loadSubtitle() {
        val chooser = JFileChooser().apply {
            fileFilter = javax.swing.filechooser.FileNameExtensionFilter(
                "Subtitle files", "srt", "ass", "ssa", "sub", "vtt",
            )
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            val path = chooser.selectedFile.absolutePath
            if (player?.addSubtitle(path) == true) {
                status.text = "Subtitle loaded: ${chooser.selectedFile.name}"
            } else {
                status.text = "Subtitle loading requires mpv."
            }
        }
    }

    private fun autoPlayFirstEpisode() {
        if (resolving) return
        if (episodes.selectedIndex >= 0 && play.isEnabled) {
            playEpisode()
        }
    }

    private fun prewarmNextEpisode() {
        if (!playbackSettings.prewarmNextEpisode) return
        val companion = catalog ?: return
        val item = detail?.item ?: return
        val line = lines.selectedItem as? CatalogLine ?: return
        val episodeIndex = episodes.selectedIndex
        val nextIndex = EpisodeAdvancePolicy.nextIndex(episodeIndex, line.episodes.size) ?: return
        val nextEpisode = line.episodes.getOrNull(nextIndex) ?: return
        val playback = playbackGeneration
        sourceOperations.execute {
            runCatching {
                val resolved = companion.play(item.sourceKey, line.flag, nextEpisode.id)
                if (resolved.supported && playback == playbackGeneration) {
                    prewarmedUrl = resolved.url
                    prewarmedHeaders = resolved.headers
                    prewarmedPlayback = playback
                    logger.info("prewarmed next episode source=${item.sourceKey} episode=${nextEpisode.id}")
                }
            }.onFailure { error ->
                logger.fine("prewarm failed: ${error.message}")
            }
        }
    }

    private fun autoAdvance(
        expectedPlayback: Long,
        item: CatalogItem,
        line: CatalogLine,
        episodeIndex: Int,
    ) {
        if (expectedPlayback != playbackGeneration) return
        val current = detail?.item ?: return
        if (current.sourceKey != item.sourceKey || current.id != item.id) return
        if ((lines.selectedItem as? CatalogLine)?.flag != line.flag || episodes.selectedIndex != episodeIndex) return
        if (!autoNext.isSelected) {
            status.text = "Playback completed; automatic next episode is off. Click Replay to repeat."
            return
        }
        val target = EpisodeAdvancePolicy.nextIndex(episodeIndex, line.episodes.size)
        if (target == null) {
            status.text = "Playback completed; this is the last episode. Click Replay to repeat."
            return
        }
        episodes.selectedIndex = target
        episodes.ensureIndexIsVisible(target)
        status.text = "Playback completed; loading ${line.episodes[target].name}…"
        playEpisode(reuseRunningPlayer = true)
    }

    private fun loadLibrary(favoritesOnly: Boolean, rememberNavigation: Boolean = true) {
        focusBrowse()
        automaticHomeLoad.stop()
        browseGeneration++
        showBrowsePane()
        pageTitle.text = if (favoritesOnly) "Keep" else "History"
        if (rememberNavigation) pushBrowseHistory() else {
            // Back must not resurrect a cached listing after the user deletes history.
            browseHistory.clear()
            vodSnapshot = null
            searchSnapshot = null
        }
        clearCategoryBrowsing(clearTypes = false)
        val available = realSources().map(CatalogSource::key).toSet()
        val saved = if (favoritesOnly) userLibrary.favorites(available) else userLibrary.recent(available)
        showItems(saved.map { entry ->
            CatalogItem(
                sourceKey = entry.sourceKey,
                id = entry.itemId,
                name = entry.name,
                picture = entry.picture,
                remarks = entry.episodeName.ifBlank { entry.remarks },
                year = entry.year,
            )
        })
        historyActions.isVisible = !favoritesOnly
        revalidate()
        status.text = if (saved.isEmpty()) {
            if (favoritesOnly) "No kept videos for the currently loaded sources." else "No viewing history yet."
        } else {
            "Loaded ${saved.size} ${if (favoritesOnly) "kept videos" else "recently played items"}."
        }
    }

    private fun restoreEpisodeSelection(item: CatalogItem, savedEntry: VideoLibraryEntry? = null) {
        val saved = savedEntry ?: userLibrary.find(item) ?: return
        val value = detail ?: return
        val selection = EpisodeSelectionPolicy.restore(
            value.lines.map(CatalogLine::flag),
            value.lines.map { line -> line.episodes.map { it.id } },
            saved.lineFlag,
            saved.episodeId,
        ) ?: return
        lines.selectedIndex = selection.lineIndex
        refreshEpisodes()
        episodes.selectedIndex = selection.episodeIndex
        episodes.ensureIndexIsVisible(selection.episodeIndex)
        logger.info(
            "history restored source=${item.sourceKey} item=${item.id} " +
                "lineIndex=${selection.lineIndex} episodeIndex=${selection.episodeIndex}",
        )
    }

    private fun toggleFavorite() {
        val item = items.getOrNull(results.selectedIndex) ?: return
        if (item.kind != CatalogItemKind.MEDIA) return
        val saved = userLibrary.toggleFavorite(item)
        status.text = if (saved) "Kept ${item.name}." else "Removed ${item.name} from Keep."
        updateActions()
    }

    private fun moveEpisode(delta: Int) {
        val target = episodes.selectedIndex + delta
        if (target !in 0 until episodes.model.size) {
            status.text = if (delta < 0) "Already at the first episode." else "Already at the last episode."
            return
        }
        episodes.selectedIndex = target
        episodes.ensureIndexIsVisible(target)
    }

    private fun stopExternalPlayer() {
        val starting = playbackStartupTimer.isRunning
        playbackGeneration++
        autoPlayTimer.stop()
        finishPlaybackStartup("Playback cancelled")
        finishActiveViewing(completed = false)
        if (player?.stop() != true) {
            status.text = if (starting) "Playback cancelled." else "No external player is running."
            updateActions()
            return
        }
        status.text = "Playback stopped."
        updateActions()
    }

    private fun finishActiveViewing(completed: Boolean) {
        activeViewing?.let { finishViewing(it, completed) }
    }

    private fun finishViewing(viewing: ActiveViewing, completed: Boolean) {
        if (activeViewing !== viewing) return
        saveViewingPosition(viewing)
        activeViewing = null
        val watched = viewing.watchTime.watchedMillis
        if (watched > 0) userLibrary.recordViewing(viewing.item, viewing.detail, watched, completed, viewing.privacySession)
    }

    private fun saveViewingPosition(viewing: ActiveViewing) {
        val state = viewing.lastState ?: return
        if (state.durationSeconds > 0 && state.positionSeconds > 0) {
            userLibrary.savePlaybackPosition(viewing.item, (state.positionSeconds * 1000).toLong(), (state.durationSeconds * 1000).toLong(), viewing.privacySession)
        }
    }

    /**
     * Transport for the running player. mpv owns a real playlist only when items were queued with
     * `enqueue`; otherwise fall back to stepping the episode list in the player that is open.
     */
    private fun transport(delta: Int) {
        val moved = if (delta < 0) player?.previous() else player?.next()
        if (moved == true) return
        val before = episodes.selectedIndex
        moveEpisode(delta)
        if (episodes.selectedIndex == before) return
        playEpisode(reuseRunningPlayer = true)
    }

    private fun auditSource() {
        val gateway = catalog ?: return
        val source = (sources.selectedItem as? CatalogSourceChoice)?.source ?: return
        val probe = query.text.trim()
        if (probe.isEmpty()) { error("Enter a search title to use as the audit probe."); return }
        auditWorker?.cancel(true)
        details.text = "Source audit: ${source.name}\nProbe: $probe\n\n"
        showDetailPoster(source.name, "")
        showDetailPane()
        val worker = object : SwingWorker<CatalogAuditReport, CatalogAuditStep>() {
            override fun doInBackground(): CatalogAuditReport = CatalogSourceAuditor().audit(
                gateway,
                source,
                probe,
                CatalogAuditCancellation { isCancelled },
            ) { publish(it) }

            override fun process(chunks: MutableList<CatalogAuditStep>) {
                chunks.forEach { step -> details.append(format(step) + "\n") }
                chunks.lastOrNull()?.let { status.text = "Audit ${it.stage.displayName()}: ${it.status.displayName()}" }
            }

            override fun done() {
                auditWorker = null
                if (isCancelled) {
                    status.text = "Source audit cancelled."
                } else {
                    runCatching { get() }
                        .onSuccess { report -> status.text = "Source audit: ${report.status.displayName()}." }
                        .onFailure { error(rootMessage(it)) }
                }
                updateActions()
            }
        }
        auditWorker = worker
        status.text = "Auditing ${source.name}…"
        updateActions()
        worker.execute()
    }

    private fun format(step: CatalogAuditStep): String =
        "${step.stage.displayName().padEnd(9)} ${step.status.displayName().padEnd(11)} " +
            "${step.elapsedMillis} ms · ${step.message}"

    private fun com.wuming.tv.core.catalog.CatalogAuditStage.displayName(): String =
        name.lowercase().replaceFirstChar(Char::uppercase)

    private fun CatalogAuditStatus.displayName(): String =
        name.lowercase().replaceFirstChar(Char::uppercase)

    private fun clearDetails() {
        showBrowsePane()
        showDetailPoster("", "")
        detailPoster.toolTipText = "Double-click a media poster to load details"
        details.text = "Select a poster to preview it. Double-click or choose Details to load episodes."
        lines.model = DefaultComboBoxModel()
        model(episodes).clear()
    }

    private fun previewSelection() {
        val item = items.getOrNull(results.selectedIndex) ?: return
        val discovery = source(item.sourceKey)?.indexProvider == true
        if (detail?.item?.let { it.sourceKey == item.sourceKey && it.id == item.id } == true) return
        detail = null
        lines.model = DefaultComboBoxModel()
        model(episodes).clear()
        showDetailPoster(item.name, item.picture)
        detailPoster.toolTipText = when (item.kind) {
            CatalogItemKind.MEDIA -> if (discovery) "Double-click to search all sites" else "Double-click to load details"
            CatalogItemKind.FOLDER -> "Double-click to open folder"
            CatalogItemKind.ACTION -> "Provider action"
        }
        details.text = buildString {
            appendLine(item.name)
            appendLine(sourceName(item.sourceKey))
            item.year.takeIf(String::isNotBlank)?.let { appendLine("Year: $it") }
            item.remarks.takeIf(String::isNotBlank)?.let { appendLine("Status: $it") }
            append(
                when (item.kind) {
                    CatalogItemKind.MEDIA -> if (discovery) {
                        "\nOpen this discovery card to search all enabled providers for playable versions."
                    } else {
                        "\nLoad details to browse playback lines and episodes."
                    }
                    CatalogItemKind.FOLDER -> "\nOpen this folder to browse its media with the current filters."
                    CatalogItemKind.ACTION -> "\nThis is a provider action, not a playable media item."
                },
            )
        }
    }

    private fun updateStatusPresentation() {
        val showEmpty = items.isEmpty() && !showingDetails
        emptyState.text = status.text
        emptyState.isVisible = showEmpty
        status.isVisible = !showEmpty
    }

    private fun showBrowsePane() {
        showingDetails = false
        updateStatusPresentation()
        browserCards.show(browserContent, BROWSER_RESULTS)
        updateActions()
    }

    private fun showDetailPane() {
        showingDetails = true
        updateStatusPresentation()
        browserCards.show(browserContent, BROWSER_DETAILS)
        updateActions()
        detailBack.requestFocusInWindow()
    }

    private fun pushBrowseHistory() {
        browseHistory.push(currentSnapshot())
        updateCategoryActions()
    }

    private fun currentSnapshot(): BrowseSnapshot {
        val choices = (0 until types.itemCount).map(types::getItemAt)
        val selectedSource = sources.selectedItem as? CatalogSourceChoice
        return BrowseSnapshot(
            items = allItems.toList(),
            thumbnailPage = thumbnailPage,
            selectedItemIndex = results.selectedIndex,
            query = query.text,
            showAll = selectedSource?.isShowAll == true,
            sourceKey = selectedSource?.source?.key,
            status = status.text,
            types = choices,
            currentType = currentCategoryType,
            currentSourceKey = currentCategorySourceKey,
            currentCategoryId = currentCategoryId,
            currentPage = currentCategoryPage,
            selections = categorySelections.toMap(),
            visibleFilterScopes = visibleFilterScopes.toSet(),
            categoryStack = categoryStack.toList(),
        )
    }

    private fun restoreBrowseSnapshot(snapshot: BrowseSnapshot) {
        automaticHomeLoad.stop()
        browseGeneration++
        renderedHomeRequest = null
        updatingSources = true
        sources.selectedIndex = if (snapshot.showAll) {
            0
        } else {
            (0 until sources.itemCount).firstOrNull { sources.getItemAt(it).source?.key == snapshot.sourceKey }
                ?: sources.selectedIndex
        }
        updatingSources = false
        query.text = snapshot.query
        updatingBrowseControls = true
        types.model = DefaultComboBoxModel(snapshot.types.toTypedArray())
        currentCategoryType = snapshot.currentType
        currentCategorySourceKey = snapshot.currentSourceKey
        currentCategoryId = snapshot.currentCategoryId
        currentCategoryPage = snapshot.currentPage
        types.selectedIndex = snapshot.currentType?.let { type ->
            snapshot.types.indexOfFirst { it.sourceKey == snapshot.currentSourceKey && it.type.id == type.id }
        } ?: -1
        updatingBrowseControls = false
        categorySelections.clear()
        categorySelections.putAll(snapshot.selections)
        visibleFilterScopes.clear()
        visibleFilterScopes.addAll(snapshot.visibleFilterScopes)
        categoryStack.clear()
        categoryStack.addAll(snapshot.categoryStack)
        snapshot.currentType?.let { type ->
            snapshot.currentSourceKey?.let { sourceKey ->
                filterSelections.remember(sourceKey, type, snapshot.selections)
            }
        }
        showItems(snapshot.items, page = snapshot.thumbnailPage)
        results.selectedIndex = snapshot.selectedItemIndex.coerceIn(-1, items.lastIndex)
        if (results.selectedIndex >= 0) results.ensureIndexIsVisible(results.selectedIndex)
        rebuildTypeBar()
        rebuildFilters()
        applyPageMode()
        status.text = snapshot.status
        updateActions()
        logger.info(
            "catalogue back restored items=${snapshot.items.size} query=${snapshot.query.isNotBlank()} " +
                "type=${snapshot.currentType?.id ?: "home"} remaining=${browseHistory.size}",
        )
    }

    private fun showDetailPoster(title: String, picture: String) {
        val generation = ++detailPosterGeneration
        detailPoster.icon = thumbnails.iconFor(title, picture) {
            if (generation == detailPosterGeneration) {
                detailPoster.icon = thumbnails.iconFor(title, picture)
                detailPoster.repaint()
            }
        }
    }

    private fun unavailableReason(key: String): String? =
        if (localExecutors.containsKey(key)) null else unavailableSourceReasons[key] ?: "runtime not active"

    private fun showUnavailableSelection(): Boolean {
        val selected = (sources.selectedItem as? CatalogSourceChoice)?.source ?: return false
        val reason = unavailableReason(selected.key) ?: return false
        browseGeneration++
        homeLoadGeneration++
        renderedHomeRequest = null
        clearCategoryBrowsing(clearTypes = true)
        showItems(emptyList())
        status.text = "${selected.name}: $reason. See Settings → Sites and required runtimes."
        updateActions()
        return true
    }

    private fun realSources(): List<CatalogSource> = CatalogSourceRoster.runnable(
        configuredSourceCapabilities.values.toList(), catalog?.sources().orEmpty(),
    )

    private fun source(key: String): CatalogSource? = realSources().firstOrNull { it.key == key }

    private fun sourceName(key: String): String = realSources()
        .firstOrNull { it.key == key }?.name.orEmpty().ifBlank { key }

    private fun updateActions() {
        val selectedItem = items.getOrNull(results.selectedIndex)
        val selectedSource = (sources.selectedItem as? CatalogSourceChoice)?.source
        val searchTargets = selectedCatalogTargets(realSources())
        search.isEnabled = catalog != null && searchTargets.any(CatalogSource::searchable) && !resolving
        audit.isEnabled = catalog != null && selectedSource?.key?.let { unavailableReason(it) == null } == true && auditWorker == null &&
            selectedSource?.searchable == true && !selectedSource.indexProvider
        stopAudit.isEnabled = auditWorker != null
        loadDetails.isEnabled = catalog != null && selectedItem?.kind != CatalogItemKind.ACTION && selectedItem != null && !resolving
        loadDetails.text = when {
            selectedItem?.kind == CatalogItemKind.FOLDER -> "Open folder"
            selectedItem?.sourceKey?.let(::source)?.indexProvider == true -> "Search all sites"
            else -> "Details"
        }
        play.isEnabled = player != null && detail != null && episodes.selectedIndex >= 0 && !resolving
        replay.isEnabled = player != null && detail != null && episodes.selectedIndex >= 0 && !resolving
        keep.isEnabled = selectedItem?.kind == CatalogItemKind.MEDIA &&
            selectedItem.sourceKey.let(::source)?.indexProvider != true
        keep.text = selectedItem?.takeIf { it.kind == CatalogItemKind.MEDIA }?.let {
            if (userLibrary.isFavorite(it)) "Unkeep" else "Keep"
        } ?: "Keep"
        previous.isEnabled = detail != null && episodes.selectedIndex > 0 && !resolving
        next.isEnabled = detail != null && episodes.selectedIndex >= 0 && episodes.selectedIndex + 1 < episodes.model.size && !resolving
        val hasPlayer = player?.isRunning() == true
        vodControls.isVisible = pageMode != CatalogPageMode.SEARCH && !showingDetails
        searchFields.isVisible = pageMode == CatalogPageMode.SEARCH && !showingDetails
        loadDetails.isVisible = !showingDetails
        episodeActions.isVisible = showingDetails && detail != null
        playerActions.isVisible = hasPlayer || resolving
        playerStop.isEnabled = hasPlayer || resolving
        playerPrevious.isEnabled = hasPlayer
        playerNext.isEnabled = hasPlayer
        playerPause.isEnabled = hasPlayer && player?.name?.equals("mpv", true) == true
        speedButton.isEnabled = hasPlayer && player?.name?.equals("mpv", true) == true
        subtitleButton.isEnabled = hasPlayer && player?.name?.equals("mpv", true) == true
        playerPrevious.toolTipText = "Previous item in the running player, or the previous episode"
        playerNext.toolTipText = "Next item in the running player, or the next episode"
        playerStop.toolTipText = "Stop the running external player"
        playerPause.toolTipText = "Pause/resume playback (mpv only)"
        speedButton.toolTipText = "Cycle playback speed (mpv only)"
        subtitleButton.toolTipText = "Load a subtitle file (mpv only)"
        updateCategoryActions()
    }

    private fun replaceCatalog(value: CatalogGateway?, reason: String) {
        catalog = value
        catalogGeneration++
        logger.info("catalog gateway changed generation=$catalogGeneration reason=$reason available=${value != null}")
    }

    private fun <T> run(
        message: String,
        operation: () -> T,
        expectedGeneration: Long? = null,
        expectedPlayback: Long? = null,
        completion: () -> Unit = {},
        success: (T) -> Unit,
    ) {
        status.text = message
        logger.info("operation start label=$message generation=${expectedGeneration ?: "independent"}")
        object : SwingWorker<T, Void>() {
            override fun doInBackground(): T = operation()
            override fun done() {
                try {
                    if (closed) return
                    // Catalogue generation does not change between episode clicks. Check the
                    // playback request too, or an older resolve can open a second/stale player.
                    if (expectedPlayback != null && expectedPlayback != playbackGeneration) {
                        logger.info("playback resolution ignored superseded request=$expectedPlayback current=$playbackGeneration")
                        return
                    }
                    if (expectedGeneration != null && expectedGeneration != catalogGeneration) {
                        if (expectedPlayback != null) {
                            finishPlaybackStartup("Playback cancelled: source changed")
                            updateActions()
                        }
                        logger.info(
                            "operation ignored stale label=$message expectedGeneration=$expectedGeneration " +
                                "currentGeneration=$catalogGeneration",
                        )
                        return
                    }
                    runCatching { get() }.onSuccess {
                        logger.info("operation completed label=$message")
                        success(it)
                    }.onFailure {
                        logger.log(Level.WARNING, "operation failed label=$message error=${rootMessage(it)}", it)
                        if (expectedPlayback != null) finishPlaybackStartup("Playback failed")
                        error(rootMessage(it))
                    }
                } finally {
                    completion()
                }
            }
        }.execute()
    }

    private fun error(message: String) {
        status.text = message
        updateActions()
    }

    private fun rootMessage(error: Throwable): String {
        var current = error
        while (current.cause != null) current = current.cause!!
        return current.message ?: current.javaClass.simpleName
    }

    private fun <T> model(list: JList<T>): DefaultListModel<T> = list.model as DefaultListModel<T>
}

private data class ActiveViewing(
    val item: CatalogItem,
    val detail: CatalogDetail?,
    val privacySession: com.wuming.tv.core.library.ViewingPrivacyPolicy.Session,
    val watchTime: com.wuming.tv.core.playback.ObservedWatchTime = com.wuming.tv.core.playback.ObservedWatchTime(),
    var lastState: PlaybackState? = null,
    var lastSavedAtMillis: Long = 0L,
)

private data class CatalogSearchOutcome(
    val items: List<CatalogItem>,
    val types: List<CatalogTypeChoice> = emptyList(),
    val failures: List<Pair<CatalogSource, Throwable>>,
    val returnedSources: Int,
)

private data class CategoryState(
    val type: CatalogType,
    val sourceKey: String,
    val typeId: String,
    val filters: Map<String, String>,
    val page: Int,
)

private enum class CatalogPageMode { VOD, SEARCH }

private data class BrowseSnapshot(
    val items: List<CatalogItem>,
    val thumbnailPage: Int,
    val selectedItemIndex: Int,
    val query: String,
    val showAll: Boolean,
    val sourceKey: String?,
    val status: String,
    val types: List<CatalogTypeChoice>,
    val currentType: CatalogType?,
    val currentSourceKey: String?,
    val currentCategoryId: String?,
    val currentPage: CatalogPage?,
    val selections: Map<String, String>,
    val visibleFilterScopes: Set<Pair<String, String>>,
    val categoryStack: List<CategoryState>,
)

private const val AUTOMATIC_HOME_SETTLE_MILLIS = 2_000
private const val MAX_CONCURRENT_SOURCES = 4
private const val TYPE_RAIL_HEIGHT = 44
private const val FILTER_ROW_HEIGHT = 48
private const val BROWSER_RESULTS = "results"
private const val BROWSER_DETAILS = "details"
private const val PROGRESSIVE_HOME_MERGE_RETRY_MILLIS = 25
private const val EPISODE_PREFERRED_ROWS = 3
private const val EPISODE_CELL_WIDTH = 220
private const val EPISODE_CELL_HEIGHT = 40
private const val EPISODE_BROWSER_HEIGHT = EPISODE_PREFERRED_ROWS * EPISODE_CELL_HEIGHT + 28
private const val DETAIL_OVERVIEW_MIN_HEIGHT = 170

internal data class CatalogSourceChoice(
    val name: String,
    val source: CatalogSource?,
) {
    val isShowAll: Boolean get() = source == null
}

internal object CatalogSourceChoices {
    fun from(sources: List<CatalogSource>, includeShowAll: Boolean = true): List<CatalogSourceChoice> = buildList {
        if (includeShowAll && sources.isNotEmpty()) add(CatalogSourceChoice("Search All", null))
        sources.forEach { add(CatalogSourceChoice(it.name, it)) }
    }

    /** Mirrors Android Home selection: saved provider, otherwise the first configured provider. */
    fun initialSelectionIndex(
        choices: List<CatalogSourceChoice>,
        preferredKey: String?,
        explicitlyShowAll: Boolean,
    ): Int {
        if (choices.isEmpty()) return -1
        if (explicitlyShowAll) return 0
        return choices.indexOfFirst { it.source?.key == preferredKey }
            .takeIf { it > 0 }
            ?: choices.indexOfFirst { !it.isShowAll }
    }
}

internal data class CatalogTypeChoice(
    val name: String,
    val sourceKey: String,
    val type: CatalogType,
    val indexProvider: Boolean = false,
)

internal object CatalogTypeChoices {
    fun from(source: CatalogSource, types: List<CatalogType>, qualifySource: Boolean): List<CatalogTypeChoice> =
        types.map { type ->
            CatalogTypeChoice(
                name = if (qualifySource && !source.indexProvider) "${source.name} · ${type.name}" else type.name,
                sourceKey = source.key,
                type = type,
                indexProvider = source.indexProvider,
            )
        }

    /** Android's configured index/home provider owns the leading Home type rail. */
    fun homeFirst(choices: List<CatalogTypeChoice>): List<CatalogTypeChoice> =
        choices.sortedBy { if (it.indexProvider) 0 else 1 }
}

/** Prevents slow ordinary providers from hiding Android's configured index/home type rail. */
internal object CatalogHomeSourceOrder {
    fun prioritize(sources: List<CatalogSource>): List<CatalogSource> =
        sources.sortedBy { if (it.indexProvider) 0 else 1 }
}

private class CatalogItemRenderer(
    private val thumbnails: CatalogThumbnailCache,
    private val thumbnailReady: () -> Unit,
    private val sourceName: (String) -> String,
) : JPanel(BorderLayout(0, 5)), ListCellRenderer<CatalogItem> {
    private val poster = JLabel()
    private val title = JLabel()
    private val metadata = JLabel()
    private val scaledIcons = object : LinkedHashMap<ScaledIconKey, javax.swing.ImageIcon>(48, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ScaledIconKey, javax.swing.ImageIcon>?): Boolean =
            size > 96
    }

    init {
        isOpaque = true
        poster.horizontalAlignment = JLabel.CENTER
        poster.verticalAlignment = JLabel.CENTER
        poster.preferredSize = Dimension(CatalogThumbnailCache.WIDTH, CatalogThumbnailCache.HEIGHT)
        title.font = title.font.deriveFont(Font.BOLD, DesktopTvTheme.px(15).toFloat())
        metadata.font = metadata.font.deriveFont(DesktopTvTheme.px(13).toFloat())
        add(poster, BorderLayout.NORTH)
        add(JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(title)
            add(metadata)
        }, BorderLayout.CENTER)
    }

    override fun getListCellRendererComponent(
        list: JList<out CatalogItem>,
        value: CatalogItem,
        index: Int,
        selected: Boolean,
        focus: Boolean,
    ): Component {
        title.text = value.name.ifBlank { "Untitled" }
        title.toolTipText = value.name
        metadata.text = listOfNotNull(
            when (value.kind) {
                CatalogItemKind.MEDIA -> null
                CatalogItemKind.FOLDER -> "Folder"
                CatalogItemKind.ACTION -> "Action"
            },
            sourceName(value.sourceKey).takeIf(String::isNotBlank),
            value.remarks.takeIf(String::isNotBlank) ?: value.year.takeIf(String::isNotBlank),
        ).joinToString(" · ")
        metadata.toolTipText = metadata.text

        background = if (selected) list.selectionBackground else list.background
        val foreground = if (selected) list.selectionForeground else list.foreground
        title.foreground = foreground
        metadata.foreground = if (selected) foreground else UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
        border = if (focus) {
            BorderFactory.createCompoundBorder(
                UIManager.getBorder("List.focusCellHighlightBorder"),
                BorderFactory.createEmptyBorder(5, 5, 5, 5),
            )
        } else {
            BorderFactory.createEmptyBorder(6, 6, 6, 6)
        }
        // Reserve measured text, gap and focus insets before assigning artwork space.
        // A fixed text band clips metadata when fonts or desktop scale change.
        val textHeight = title.preferredSize.height + metadata.preferredSize.height
        val posterWidth = (list.fixedCellWidth - insets.left - insets.right).coerceAtLeast(1)
        val posterHeight = (list.fixedCellHeight - insets.top - insets.bottom -
            (layout as BorderLayout).vgap - textHeight).coerceAtLeast(1)
        poster.preferredSize = Dimension(posterWidth, posterHeight)
        poster.icon = scaledIcon(
            thumbnails.iconFor(value.name, value.picture, thumbnailReady),
            posterWidth,
            posterHeight,
        )
        return this
    }

    companion object {
        const val BASE_CARD_WIDTH = 180
        const val BASE_CARD_HEIGHT = 246
    }

    private fun scaledIcon(icon: javax.swing.ImageIcon, availableWidth: Int, availableHeight: Int): javax.swing.ImageIcon {
        if (icon.iconWidth <= 0 || icon.iconHeight <= 0) return icon
        val ratio = minOf(
            availableWidth.toDouble() / icon.iconWidth,
            availableHeight.toDouble() / icon.iconHeight,
        )
        val width = (icon.iconWidth * ratio).toInt().coerceAtLeast(1)
        val height = (icon.iconHeight * ratio).toInt().coerceAtLeast(1)
        if (width == icon.iconWidth && height == icon.iconHeight) return icon
        val key = ScaledIconKey(icon, width, height)
        return scaledIcons.getOrPut(key) {
            PosterImages.pixels(icon)?.let { PosterImages.icon(it, width, height) } ?: icon
        }
    }

    private data class ScaledIconKey(val source: javax.swing.ImageIcon, val width: Int, val height: Int)
}

/** Rounded provider category/filter control matching Leanback's focusable chips. */
private class LeanbackChip(
    text: String,
    compact: Boolean = false,
) : JToggleButton(text) {

    init {
        font = font.deriveFont(Font.BOLD, DesktopTvTheme.px(if (compact) 14 else 15).toFloat())
        foreground = DesktopTvTheme.TEXT
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        isRolloverEnabled = true
        border = BorderFactory.createEmptyBorder(
            DesktopTvTheme.px(if (compact) 7 else 9),
            DesktopTvTheme.px(if (compact) 13 else 17),
            DesktopTvTheme.px(if (compact) 7 else 9),
            DesktopTvTheme.px(if (compact) 13 else 17),
        )
    }

    override fun paintComponent(graphics: Graphics) {
        val canvas = graphics.create() as Graphics2D
        canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        canvas.color = when {
            model.isSelected -> DesktopTvTheme.CHIP_SELECTED
            model.isRollover -> DesktopTvTheme.TILE_SELECTED
            else -> DesktopTvTheme.CHIP
        }
        canvas.fillRoundRect(0, 0, width, height, height, height)
        if (hasFocus()) {
            canvas.color = DesktopTvTheme.TEXT
            canvas.drawRoundRect(1, 1, (width - 3).coerceAtLeast(1), (height - 3).coerceAtLeast(1), height, height)
        }
        canvas.dispose()
        super.paintComponent(graphics)
    }
}

@Suppress("UNCHECKED_CAST")
private class NamedRenderer<T>(private val name: (T?) -> String) : javax.swing.DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean,
    ): java.awt.Component = super.getListCellRendererComponent(
        list, name(value as? T), index, selected, focus,
    ).also {
        border = BorderFactory.createEmptyBorder(
            DesktopTvTheme.px(5),
            DesktopTvTheme.px(9),
            DesktopTvTheme.px(5),
            DesktopTvTheme.px(9),
        )
        verticalAlignment = javax.swing.SwingConstants.CENTER
    }
}

private class EpisodeRenderer : javax.swing.DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean,
    ): java.awt.Component = super.getListCellRendererComponent(
        list,
        EpisodeDisplayName.format(value?.toString().orEmpty()),
        index,
        selected,
        focus,
    ).also { component ->
        (component as? JLabel)?.apply {
            border = BorderFactory.createEmptyBorder(5, 9, 5, 9)
            toolTipText = value?.toString().orEmpty()
        }
    }
}

/** Keeps cloud-drive filenames from hiding the useful trailing episode number. */
internal object EpisodeDisplayName {
    fun format(value: String): String = com.wuming.tv.core.catalog.EpisodeDisplayName.format(value)
}

internal object EpisodeGridSizing {
    fun cellWidth(availableWidth: Int, preferredWidth: Int): Int {
        if (availableWidth <= 0) return preferredWidth
        val columns = ((availableWidth + preferredWidth / 2) / preferredWidth).coerceAtLeast(1)
        return (availableWidth / columns).coerceAtLeast(1)
    }
}

internal object CatalogThumbnailSizing {
    private const val BASE_VIEWPORT_FRACTION = 0.16

    fun cellWidth(viewportWidth: Int, scalePercent: Int): Int =
        (viewportWidth * BASE_VIEWPORT_FRACTION * scalePercent / 100.0).roundToInt().coerceAtLeast(1)
}
