package com.wuming.tv.desktop.ui

import com.sun.jna.Native
import com.wuming.tv.desktop.config.DesktopPlaybackSettings
import com.wuming.tv.desktop.player.EmbeddedVideoHost
import com.wuming.tv.desktop.player.ExternalPlayerAdapter
import com.wuming.tv.desktop.player.PlaybackState
import com.wuming.tv.desktop.player.PlaybackStateCallback
import java.awt.BorderLayout
import java.awt.Canvas
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.prefs.Preferences
import javax.swing.*

/** Persistent heavyweight peer: fullscreen resizes this surface, never reparents or recreates it. */
internal class EmbeddedPlaybackPanel(
    private val player: ExternalPlayerAdapter?,
    preferences: Preferences,
    private val visibilityChanged: (Boolean) -> Unit = {},
    private val fullscreenChanged: (Boolean) -> Unit = {},
    private val stopRequested: () -> Unit = {},
    private val nativeWindow: (Canvas) -> Long = Native::getComponentID,
    private val supported: () -> Boolean = {
        !GraphicsEnvironment.isHeadless() && System.getProperty("os.name").startsWith("Linux") &&
            !System.getenv("DISPLAY").isNullOrBlank() && Toolkit.getDefaultToolkit().javaClass.name.contains("XToolkit")
    },
) : JPanel(BorderLayout()), EmbeddedVideoHost, AutoCloseable {
    private val settings = DesktopPlaybackSettings(preferences)
    internal val surface = Canvas().apply { background = Color.BLACK; isFocusable = true }
    private val title = JLabel("Now playing")
    private val pause = JButton("Pause")
    private val stop = JButton("Stop")
    private val progress = JSlider(0, 1000, 0)
    private val elapsed = JLabel("0:00 / 0:00")
    private val speed = JComboBox(arrayOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 3.0))
    private val fullscreen = JButton("Fullscreen")
    private val heading = JPanel(BorderLayout(12, 0))
    private val controls = JPanel(BorderLayout(0, 6))
    private var fullscreenOverlay: FullscreenPlaybackOverlay? = null
    internal val fullscreenControlsVisible: Boolean get() = fullscreenOverlay?.controlsVisible == true
    private var state = PlaybackState()
    private var updating = false
    internal var fullscreenActive = false
        private set
    private val subscription = player?.addPlaybackStateListener(PlaybackStateCallback { snapshot ->
        SwingUtilities.invokeLater { updateState(snapshot) }
    })

    init {
        background = DesktopTvTheme.SURFACE
        border = BorderFactory.createLineBorder(DesktopTvTheme.DIVIDER)
        preferredSize = Dimension(680, 480)
        minimumSize = Dimension(380, 240)
        title.foreground = DesktopTvTheme.TEXT
        title.font = title.font.deriveFont(Font.BOLD, 16f)
        title.minimumSize = Dimension(0, 24)
        heading.apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(12, 14, 12, 14)
            add(JPanel(BorderLayout(0, 4)).apply {
                isOpaque = false
                add(JLabel("NOW PLAYING").apply {
                    foreground = DesktopTvTheme.ACCENT
                    font = font.deriveFont(Font.BOLD, 10f)
                }, BorderLayout.NORTH)
                add(title, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
            add(stop.apply {
                putClientProperty("style", "quiet")
                addActionListener {
                    // Process shutdown can wait for IPC; keep it off the event thread.
                    stopRequested()
                    Thread({ player?.stop() }, "tvbox-embedded-stop").apply { isDaemon = true; start() }
                }
            }, BorderLayout.EAST)
        }
        add(heading, BorderLayout.NORTH)
        add(surface, BorderLayout.CENTER)
        controls.apply {
            background = DesktopTvTheme.SURFACE
            border = BorderFactory.createEmptyBorder(8, 12, 10, 12)
            add(JPanel(BorderLayout(8, 0)).apply {
                isOpaque = false
                add(progress, BorderLayout.CENTER)
                add(elapsed.apply {
                    foreground = DesktopTvTheme.MUTED_TEXT
                    font = font.deriveFont(11f)
                }, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            val transport = JPanel(BorderLayout(8, 0)).apply {
                isOpaque = false
                add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                    isOpaque = false
                    add(JButton("−10s").apply { addActionListener { player?.seekRelative(-10.0) } })
                    add(pause.apply {
                        putClientProperty("style", "primary")
                        addActionListener { player?.togglePause() }
                    })
                    add(JButton("+10s").apply { addActionListener { player?.seekRelative(10.0) } })
                }, BorderLayout.WEST)
                add(fullscreen.apply { addActionListener { setFullscreen(!fullscreenActive) } }, BorderLayout.EAST)
            }
            val options = JPanel(BorderLayout(8, 0)).apply {
                isOpaque = false
                add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                    isOpaque = false
                    add(JToggleButton("Mute").apply { addActionListener { player?.setMuted(isSelected) } })
                    add(JSlider(0, 100, 100).apply {
                        preferredSize = Dimension(80, 30)
                        accessibleContext.accessibleName = "Playback volume"
                        toolTipText = "Volume"
                        addChangeListener { player?.setVolume(value) }
                    })
                    add(speed.apply {
                        selectedItem = 1.0
                        accessibleContext.accessibleName = "Playback speed"
                        renderer = object : DefaultListCellRenderer() {
                            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int,
                                selected: Boolean, focus: Boolean): java.awt.Component =
                                super.getListCellRendererComponent(list, "${value}×", index, selected, focus)
                        }
                        addActionListener { if (!updating) player?.setSpeed(selectedItem as Double) }
                    })
                }, BorderLayout.WEST)
                add(JButton("More…").apply {
                    toolTipText = "Subtitles and external playback"
                    addActionListener {
                        JPopupMenu().apply {
                            add(JMenuItem("Load subtitles…").apply { addActionListener {
                                val chooser = JFileChooser()
                                if (chooser.showOpenDialog(this@EmbeddedPlaybackPanel) == JFileChooser.APPROVE_OPTION)
                                    player?.addSubtitle(chooser.selectedFile.absolutePath)
                            } })
                            add(JMenuItem("Open externally").apply { addActionListener { player?.openExternally() } })
                        }.show(this, 0, height)
                    }
                }, BorderLayout.EAST)
            }
            add(JPanel(java.awt.GridLayout(2, 1, 0, 6)).apply {
                isOpaque = false
                add(transport)
                add(options)
            }, BorderLayout.CENTER)
        }
        add(controls, BorderLayout.SOUTH)
        progress.accessibleContext.accessibleName = "Playback position"
        progress.addChangeListener {
            if (!updating && !progress.valueIsAdjusting && state.durationSeconds > 0) player?.seekTo(state.durationSeconds * progress.value / 1000.0)
        }
        surface.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) { surface.requestFocusInWindow() }
            override fun mouseClicked(event: MouseEvent) { if (event.clickCount == 2) setFullscreen(!fullscreenActive) }
        })
        surface.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(event: KeyEvent) {
                when (event.keyCode) {
                    KeyEvent.VK_SPACE -> player?.togglePause()
                    KeyEvent.VK_LEFT -> player?.seekRelative(-10.0)
                    KeyEvent.VK_RIGHT -> player?.seekRelative(10.0)
                    KeyEvent.VK_ESCAPE -> if (fullscreenActive) setFullscreen(false)
                    KeyEvent.VK_F11 -> setFullscreen(!fullscreenActive)
                    else -> return
                }
                event.consume()
            }
        })
        isVisible = false
    }

    override fun acquire(title: String?, fullscreen: Boolean): Long? {
        if (!settings.embeddedPlayback || player?.name?.equals("mpv", true) != true || !supported()) return null
        var result: Long? = null
        onEdt {
            this.title.text = title?.takeIf { it.isNotBlank() } ?: "Now playing"
            this.title.toolTipText = this.title.text
            val wasVisible = isVisible
            isVisible = true
            if (!wasVisible) visibilityChanged(true)
            parent?.validate()
            result = try { nativeWindow(surface).takeIf { it > 0 } } catch (_: Exception) { null } catch (_: LinkageError) { null }
            if (result != null) {
                setFullscreen(fullscreen || (wasVisible && fullscreenActive))
                surface.requestFocusInWindow()
            } else hidePlayback()
        }
        return result
    }

    override fun externalPlayback() = later { hidePlayback() }
    override fun playbackClosed() = later { hidePlayback() }

    fun setFullscreen(value: Boolean) {
        if (value == fullscreenActive || (value && !isVisible)) return
        fullscreenActive = value
        fullscreen.text = if (value) "Exit fullscreen" else "Fullscreen"
        heading.isVisible = !value
        controls.isVisible = !value
        border = if (value) null else BorderFactory.createLineBorder(DesktopTvTheme.DIVIDER)
        fullscreenOverlay?.close()
        fullscreenOverlay = null
        fullscreenChanged(value)
        revalidate()
        if (value) {
            SwingUtilities.getWindowAncestor(this)?.takeIf { it.isShowing }?.let { owner ->
                fullscreenOverlay = FullscreenPlaybackOverlay(owner,
                    { player?.togglePause() }, { player?.seekRelative(it) }, { player?.seekTo(it) },
                    { setFullscreen(false) }).also { it.updateState(state) }
            }
            // The button that entered fullscreen is now hidden; return keyboard control to video.
            SwingUtilities.invokeLater { if (fullscreenActive) surface.requestFocusInWindow() }
        }
    }

    internal fun updateState(snapshot: PlaybackState) {
        state = snapshot
        fullscreenOverlay?.updateState(snapshot)
        updating = true
        try {
            speed.selectedItem = snapshot.speed
            pause.text = if (snapshot.paused) "Play" else "Pause"
            progress.isEnabled = snapshot.durationSeconds > 0
            if (!progress.valueIsAdjusting) progress.value = if (snapshot.durationSeconds > 0)
                (snapshot.positionSeconds / snapshot.durationSeconds * 1000).toInt().coerceIn(0, 1000) else 0
            elapsed.text = "${time(snapshot.positionSeconds)} / ${time(snapshot.durationSeconds)}"
        } finally { updating = false }
    }

    private fun time(value: Double): String {
        val seconds = value.toLong().coerceAtLeast(0)
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    private fun hidePlayback() {
        setFullscreen(false)
        isVisible = false
        visibilityChanged(false)
    }

    override fun close() { subscription?.close(); onEdt { hidePlayback() } }
    private fun onEdt(action: () -> Unit) { if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action) }
    private fun later(action: () -> Unit) { if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeLater(action) }
}
