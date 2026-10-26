package com.wuming.tv.desktop.ui

import com.wuming.tv.core.media.TvHomeAction
import com.wuming.tv.core.media.TvHomeLayoutSpec
import com.wuming.tv.desktop.DesktopSmokeCheck
import com.wuming.tv.desktop.cloud.DesktopCloudAccounts
import com.wuming.tv.desktop.cloud.DesktopCloudPlaybackResolver
import com.wuming.tv.desktop.diagnostics.DesktopLogging
import com.wuming.tv.desktop.player.ExternalPlayerDiscovery
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.RenderingHints
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.WindowConstants

class DesktopWindow {

    private val repeatRequests = RepeatRequestGate()
    private val layoutSpec = TvHomeLayoutSpec.desktop()
    private val player = ExternalPlayerDiscovery.discover()
    private val frame = JFrame("無名")
    private val lanRelay = com.wuming.tv.desktop.server.DesktopLanRelay()
    private var exiting = false
    private var assistanceMonitor: PlaybackAssistanceMonitor? = null
    private val tray = DesktopTray(::show, ::showAssistanceMonitor, ::exitDesktop,
        { frame.isVisible = false }, { frame.extendedState = JFrame.ICONIFIED })
    private val relayPreferences = LanRelayPreferencesPanel(lanRelay, ::exitDesktop, ::showAssistanceMonitor)
    private val cloudAccountState = DesktopCloudAccounts()
    private val cloudPlayback = DesktopCloudPlaybackResolver(cloudAccountState.credentials)
    private val catalog = CatalogPanel(
        player,
        cloudPlaybackResolver = cloudPlayback,
        onBackRequested = ::navigateBack,
        onCastRequested = { playback, title, onSent ->
            openMedia.prepareVideo(playback, title, onSent)
            showAction(TvHomeAction.PUSH)
        },
    )
    private val cloudAccounts = CloudAccountsPanel(cloudAccountState, cloudPlayback)
    private val liveTv = LiveTvPanel(player)
    private val configuration = ConfigurationPanel(
        onStartupStatus = catalog::showConfigurationStatus,
        onApprovalRequired = { approve ->
            showAction(TvHomeAction.SETTINGS)
            SwingUtilities.invokeLater { approve() }
        },
        onLoaded = { value ->
            player?.configureHlsAdRules(value.hlsAdRules)
            catalog.useLocalConfiguration(value)
            liveTv.useLocalConfiguration(value)
        },
        onSourcesRestored = { catalog.addLocalExecutors(it, preserveBrowse = true) },
        onSpiderEnabled = { executor ->
            catalog.addLocalExecutor(executor)
            showAction(TvHomeAction.VOD)
        },
        onGuardedEnabled = { executors ->
            catalog.addLocalExecutors(executors)
            showAction(TvHomeAction.VOD)
        },
    )
    private val cardLayout = CardLayout()
    private val cards = JPanel(cardLayout).apply { isOpaque = false }
    private val navigation = TvHomeNavigationPanel(layoutSpec, ::showAction)
    private val openMedia = CastingPanel(player)
    private val overview = overviewPanel()
    private val clock = JLabel().apply {
        horizontalAlignment = SwingConstants.RIGHT
        font = font.deriveFont(Font.BOLD, 18f)
    }
    private val pageContext = JLabel("YOUR SPACE").apply {
        foreground = DesktopTvTheme.ACCENT
        font = font.deriveFont(Font.BOLD, 10f)
    }
    private val pageTitle = JLabel("Home").apply { font = font.deriveFont(Font.BOLD, 20f) }
    private val clockTimer = Timer(1_000) { updateClock() }
    private var startupConfigurationRequested = false
    private var currentAction: TvHomeAction? = null
    private val backAction = object : AbstractAction("← Back") {
        init { isEnabled = false }
        override fun actionPerformed(event: ActionEvent) = navigateBack()
    }

    init {
        cards.add(overview, CARD_HOME)
        cards.add(catalog, CARD_VOD)
        cards.add(liveTv, CARD_LIVE)
        cards.add(cloudAccounts, CARD_ACCOUNTS)
        cards.add(openMedia, CARD_OPEN)
        cards.add(javax.swing.JTabbedPane().apply {
            border = BorderFactory.createEmptyBorder(4, 6, 6, 6)
            addTab("Sources", configuration)
            addTab("Playback assistance", relayPreferences)
            addTab("Appearance", AppearancePreferencesPanel())
            addTab("Playback", PlaybackPreferencesPanel(java.util.prefs.Preferences.userNodeForPackage(CatalogPanel::class.java)))
        }, CARD_SETTINGS)

        frame.iconImages = DesktopAppIcon.windowImages()
        frame.isUndecorated = true
        frame.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        val workArea = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        val minimumWidth = (workArea.width * 0.46).toInt().coerceAtLeast(800)
        val minimumHeight = (workArea.height * 0.48).toInt().coerceAtLeast(540)
        val initialWidth = (workArea.width * 0.84).toInt().coerceIn(minimumWidth, workArea.width)
        val initialHeight = (workArea.height * 0.84).toInt().coerceIn(minimumHeight, workArea.height)
        frame.minimumSize = Dimension(minimumWidth, minimumHeight)
        val applicationBody = DesktopGradientPanel().apply {
            layout = BorderLayout()
            border = BorderFactory.createEmptyBorder(
                DesktopTvTheme.px(16),
                DesktopTvTheme.px(20),
                DesktopTvTheme.px(20),
                DesktopTvTheme.px(20),
            )
            add(toolbar(), BorderLayout.NORTH)
            add(homeContent(), BorderLayout.CENTER)
        }
        frame.contentPane = windowShell(applicationBody)
        DesktopTvTheme.scaleExplicitMetrics(frame.contentPane)
        frame.pack()
        frame.setSize(initialWidth, initialHeight)
        frame.setLocation(
            workArea.x + (workArea.width - initialWidth) / 2,
            workArea.y + (workArea.height - initialHeight) / 2,
        )
        frame.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) = updateResponsiveLayout()
        })
        updateResponsiveLayout()
        installBackNavigation()
        installFullscreenToggle()
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(event: WindowEvent) {
                if (!exiting && tray.keepInBackground(lanRelay.running)) return
                exiting = true
                relayPreferences.close()
                assistanceMonitor?.close(); assistanceMonitor = null
                tray.close()
                frame.dispose()
                configuration.cancelLoading()
                openMedia.close()
                clockTimer.stop()
                catalog.close()
                liveTv.close()
                cloudAccounts.close()
                player?.terminateAll()
            }
        })
        tray.install()
        updateClock()
        clockTimer.start()
    }

    private fun exitDesktop() {
        exiting = true
        frame.dispatchEvent(WindowEvent(frame, WindowEvent.WINDOW_CLOSING))
    }

    private fun showAssistanceMonitor() {
        if (exiting) return
        if (assistanceMonitor == null) assistanceMonitor = PlaybackAssistanceMonitor(lanRelay::monitorSnapshot) { assistanceMonitor = null }
        assistanceMonitor?.show()
    }

    fun show() {
        if (exiting) return
        frame.isVisible = true
        frame.extendedState = JFrame.NORMAL
        frame.toFront()
        frame.requestFocus()
        navigation.focusFirst()
        if (!startupConfigurationRequested) {
            startupConfigurationRequested = true
            showAction(TvHomeAction.VOD)
            SwingUtilities.invokeLater { configuration.loadSavedConfiguration() }
        }
    }

    private fun toolbar(): JPanel {
        val toolbarHeight = 72
        return JPanel(BorderLayout(24, 0)).apply {
            isOpaque = false
            preferredSize = Dimension(0, toolbarHeight)
            border = BorderFactory.createEmptyBorder(2, 4, 12, 4)
            add(JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                toolTipText = "Return to desktop home"
                add(JButton(backAction).apply {
                    toolTipText = "Go back (Esc)"
                    accessibleContext.accessibleName = "Go back"
                    putClientProperty("style", "quiet")
                    preferredSize = Dimension(78, 38)
                    maximumSize = preferredSize
                })
            }, BorderLayout.WEST)
            add(JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(pageContext)
                add(Box.createVerticalStrut(2))
                add(pageTitle)
            }, BorderLayout.CENTER)
            add(JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(clock)
                add(JLabel("●  DESKTOP READY").apply {
                    foreground = DesktopTvTheme.POSITIVE
                    font = font.deriveFont(Font.BOLD, 10f)
                    horizontalAlignment = SwingConstants.RIGHT
                    alignmentX = Component.RIGHT_ALIGNMENT
                })
            }, BorderLayout.EAST)
        }
    }

    private fun windowShell(body: JPanel): JPanel = JPanel(BorderLayout()).apply {
        background = DesktopTvTheme.BACKGROUND
        add(windowTitleBar(), BorderLayout.NORTH)
        add(resizeStrip(Cursor.W_RESIZE_CURSOR, ResizeEdge.WEST), BorderLayout.WEST)
        add(resizeStrip(Cursor.E_RESIZE_CURSOR, ResizeEdge.EAST), BorderLayout.EAST)
        add(resizeStrip(Cursor.S_RESIZE_CURSOR, ResizeEdge.SOUTH), BorderLayout.SOUTH)
        add(body, BorderLayout.CENTER)
    }

    private fun windowTitleBar(): JPanel {
        var pressedAt: Point? = null
        var startingBounds: Rectangle? = null
        val drag = object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                pressedAt = event.locationOnScreen
                startingBounds = frame.bounds
            }

            override fun mouseDragged(event: MouseEvent) {
                if (frame.extendedState == JFrame.MAXIMIZED_BOTH) return
                val start = pressedAt ?: return
                val bounds = startingBounds ?: return
                frame.setLocation(
                    bounds.x + event.xOnScreen - start.x,
                    bounds.y + event.yOnScreen - start.y,
                )
            }

            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2) toggleFullscreen()
            }
        }
        return JPanel(BorderLayout()).apply {
            background = DesktopTvTheme.SURFACE
            preferredSize = Dimension(0, 48)
            border = BorderFactory.createMatteBorder(0, 0, 1, 0, DesktopTvTheme.DIVIDER)
            add(JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                border = BorderFactory.createEmptyBorder(0, 14, 0, 0)
                add(JLabel(DesktopAppIcon.icon(DesktopTvTheme.px(18))))
                add(Box.createHorizontalStrut(10))
                add(JLabel("無名").apply {
                    font = font.deriveFont(Font.BOLD, 14f)
                    foreground = DesktopTvTheme.TEXT
                })
            }, BorderLayout.WEST)
            add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 8)).apply {
                isOpaque = false
                add(titleBarButton("—", "Minimize", DesktopTvTheme.MUTED_TEXT) { frame.extendedState = JFrame.ICONIFIED })
                add(titleBarButton("□", "Maximize or restore", DesktopTvTheme.MUTED_TEXT) { toggleFullscreen() })
                add(titleBarButton("×", "Close", DesktopTvTheme.POSITIVE) {
                    frame.dispatchEvent(WindowEvent(frame, WindowEvent.WINDOW_CLOSING))
                })
            }, BorderLayout.EAST)
            addMouseListener(drag)
            addMouseMotionListener(drag)
        }
    }

    private fun titleBarButton(text: String, accessibleName: String, normalColor: Color, action: () -> Unit): JButton =
        JButton(text).apply {
            putClientProperty("style", "quiet")
            preferredSize = Dimension(44, 32)
            minimumSize = preferredSize
            toolTipText = accessibleName
            accessibleContext.accessibleName = accessibleName
            isFocusable = false
            isOpaque = false
            border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
            foreground = DesktopTvTheme.currentColor(normalColor)
            font = font.deriveFont(Font.BOLD, 12f)

            // Add hover effect
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseEntered(e: java.awt.event.MouseEvent) {
                    foreground = DesktopTvTheme.ACCENT
                    repaint()
                }
                override fun mouseExited(e: java.awt.event.MouseEvent) {
                    foreground = DesktopTvTheme.currentColor(normalColor)
                    repaint()
                }
            })
            addActionListener { action() }
        }

    private fun resizeStrip(cursorType: Int, edge: ResizeEdge): JPanel {
        var pressedAt: Point? = null
        var startingBounds: Rectangle? = null
        val resize = object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                pressedAt = event.locationOnScreen
                startingBounds = frame.bounds
            }

            override fun mouseDragged(event: MouseEvent) {
                if (frame.extendedState == JFrame.MAXIMIZED_BOTH) return
                val start = pressedAt ?: return
                val original = startingBounds ?: return
                val dx = event.xOnScreen - start.x
                val dy = event.yOnScreen - start.y
                val minimum = frame.minimumSize
                when (edge) {
                    ResizeEdge.WEST -> {
                        val width = (original.width - dx).coerceAtLeast(minimum.width)
                        frame.setBounds(original.x + original.width - width, original.y, width, original.height)
                    }
                    ResizeEdge.EAST -> frame.setSize((original.width + dx).coerceAtLeast(minimum.width), original.height)
                    ResizeEdge.SOUTH -> frame.setSize(original.width, (original.height + dy).coerceAtLeast(minimum.height))
                }
            }
        }
        return JPanel().apply {
            background = DesktopTvTheme.BACKGROUND
            cursor = Cursor.getPredefinedCursor(cursorType)
            preferredSize = if (edge == ResizeEdge.SOUTH) Dimension(0, 5) else Dimension(5, 0)
            addMouseListener(resize)
            addMouseMotionListener(resize)
        }
    }

    private fun homeContent(): JPanel = JPanel(BorderLayout(18, 0)).apply {
        isOpaque = false
        add(navigation, BorderLayout.WEST)
        add(cards, BorderLayout.CENTER)
    }

    private fun updateResponsiveLayout() {
        val availableWidth = frame.contentPane.width.takeIf { it > 0 } ?: frame.width
        val logicalWidth = (availableWidth / DesktopTvTheme.uiScale).toInt()
        val railWidth = DesktopTvTheme.px(com.wuming.tv.core.design.LayoutPolicy.desktopRailWidth(logicalWidth))
        navigation.setRailWidth(railWidth)
    }

    private fun showHome() {
        catalog.cancelAutoAdvance()
        cardLayout.show(cards, CARD_HOME)
        currentAction = null
        backAction.isEnabled = false
        pageContext.text = "YOUR SPACE"
        pageTitle.text = "Home"
        navigation.showSelection(null)
        navigation.focusFirst()
    }

    private fun showAction(action: TvHomeAction) {
        if (!repeatRequests.accept("navigation:$action")) return
        if (action !in setOf(TvHomeAction.VOD, TvHomeAction.SEARCH, TvHomeAction.KEEP)) {
            catalog.cancelAutoAdvance()
        }
        val card = when (action) {
            TvHomeAction.VOD, TvHomeAction.SEARCH, TvHomeAction.KEEP -> CARD_VOD
            TvHomeAction.LIVE_TV -> CARD_LIVE
            TvHomeAction.CLOUD_ACCOUNTS -> CARD_ACCOUNTS
            TvHomeAction.PUSH -> CARD_OPEN
            TvHomeAction.SETTINGS -> CARD_SETTINGS
        }
        cardLayout.show(cards, card)
        currentAction = action
        backAction.isEnabled = true
        pageContext.text = action.contextLabel()
        pageTitle.text = action.pageTitle()
        navigation.showSelection(action)
        if (action == TvHomeAction.SEARCH) catalog.focusSearch()
        if (action == TvHomeAction.VOD) catalog.focusBrowse()
        if (action == TvHomeAction.KEEP) catalog.showKeep()
    }

    private fun installBackNavigation() {
        val root = frame.rootPane
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), ACTION_BACK)
        root.actionMap.put(ACTION_BACK, backAction)
    }

    private fun installFullscreenToggle() {
        val root = frame.rootPane
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_F11, 0), ACTION_FULLSCREEN)
        root.actionMap.put(ACTION_FULLSCREEN, object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) = toggleFullscreen()
        })
    }

    private fun toggleFullscreen() {
        frame.extendedState = if (frame.extendedState == JFrame.MAXIMIZED_BOTH) {
            JFrame.NORMAL
        } else {
            JFrame.MAXIMIZED_BOTH
        }
    }

    private fun navigateBack() {
        if (currentAction in setOf(TvHomeAction.VOD, TvHomeAction.SEARCH, TvHomeAction.KEEP) &&
            catalog.navigateBack()
        ) return
        if (currentAction != null) showHome()
    }

    private fun updateClock() {
        clock.text = LocalTime.now().format(CLOCK_FORMAT)
    }

    private fun overviewPanel(): JPanel {
        val report = DesktopSmokeCheck.verify()
        return JPanel(BorderLayout(0, 18)).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(4, 2, 2, 2)
            add(DesktopCardPanel(DesktopTvTheme.SURFACE_RAISED).apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(eyebrow("Ready when you are"))
                add(Box.createVerticalStrut(8))
                add(JLabel("What do you want to watch?").apply {
                    font = font.deriveFont(Font.BOLD, 30f)
                    alignmentX = 0f
                })
                add(Box.createVerticalStrut(8))
                add(JLabel("Jump back in, browse live channels, or open something from another device.").apply {
                    font = font.deriveFont(Font.PLAIN, 16f)
                    foreground = DesktopTvTheme.MUTED_TEXT
                    alignmentX = 0f
                })
                add(Box.createVerticalStrut(18))
                add(JPanel(GridLayout(1, 3, 12, 0)).apply {
                    isOpaque = false
                    add(homeShortcut("▶", "Discover", "Browse films and series", TvHomeAction.VOD))
                    add(homeShortcut("▣", "Live TV", "Open configured channels", TvHomeAction.LIVE_TV))
                    add(homeShortcut("★", "My library", "Continue saved titles", TvHomeAction.KEEP))
                    maximumSize = Dimension(Int.MAX_VALUE, 88)
                    alignmentX = 0f
                })
            }, BorderLayout.NORTH)
            add(DesktopCardPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(eyebrow("System overview"))
            add(Box.createVerticalStrut(10))
            add(JLabel("Everything in one place").apply {
                font = font.deriveFont(Font.BOLD, 20f)
                alignmentX = 0f
            })
            add(Box.createVerticalStrut(14))
            add(JPanel(GridLayout(1, 3, 12, 0)).apply {
                isOpaque = false
                add(metric("PLAYER", player?.name ?: "Not detected"))
                add(metric("CORE MODULES", report.components.size.toString()))
                add(metric("SHORTCUTS", "Esc · F11"))
                maximumSize = Dimension(Int.MAX_VALUE, 82)
                alignmentX = 0f
            })
            add(Box.createVerticalStrut(14))
            add(mutedLabel("Add or change content sources in Settings. Diagnostics: ${DesktopLogging.directory}").apply {
                alignmentX = 0f
            })
            }, BorderLayout.CENTER)
        }
    }

    private fun homeShortcut(symbol: String, title: String, detail: String, action: TvHomeAction): JButton =
        JButton("<html><b><font size='+1'>$symbol&nbsp;&nbsp;$title</font></b><br><font color='#9DA9BB'>$detail</font></html>").apply {
            horizontalAlignment = SwingConstants.LEFT
            putClientProperty("style", if (action == TvHomeAction.VOD) "primary" else "quiet")
            addActionListener { showAction(action) }
        }

    private fun metric(label: String, value: String): JPanel = JPanel().apply {
        isOpaque = true
        background = DesktopTvTheme.BACKGROUND_RAISED
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(DesktopTvTheme.DIVIDER),
            BorderFactory.createEmptyBorder(12, 14, 12, 14),
        )
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(JLabel(label).apply { foreground = DesktopTvTheme.SUBTLE_TEXT; font = font.deriveFont(Font.BOLD, 10f) })
        add(Box.createVerticalStrut(5))
        add(JLabel(value).apply { font = font.deriveFont(Font.BOLD, 15f) })
    }

    private fun TvHomeAction.contextLabel(): String = when (this) {
        TvHomeAction.VOD, TvHomeAction.SEARCH -> "CATALOGUE"
        TvHomeAction.LIVE_TV -> "ON AIR"
        TvHomeAction.KEEP -> "YOUR SPACE"
        TvHomeAction.PUSH -> "CONNECTED DEVICES"
        TvHomeAction.SETTINGS -> "PREFERENCES"
        TvHomeAction.CLOUD_ACCOUNTS -> "CLOUD"
    }

    private fun TvHomeAction.pageTitle(): String = when (this) {
        TvHomeAction.VOD -> "Discover"
        TvHomeAction.LIVE_TV -> "Live television"
        TvHomeAction.SEARCH -> "Search"
        TvHomeAction.KEEP -> "My library"
        TvHomeAction.PUSH -> "Cast & open"
        TvHomeAction.SETTINGS -> "Settings"
        TvHomeAction.CLOUD_ACCOUNTS -> "Cloud accounts"
    }

    private companion object {
        const val CARD_HOME = "home"
        const val CARD_VOD = "vod"
        const val CARD_LIVE = "live"
        const val CARD_ACCOUNTS = "accounts"
        const val CARD_OPEN = "open"
        const val CARD_SETTINGS = "settings"
        const val ACTION_BACK = "tvbox-back"
        const val ACTION_FULLSCREEN = "tvbox-fullscreen"
        val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }

    private enum class ResizeEdge { WEST, EAST, SOUTH }
}
