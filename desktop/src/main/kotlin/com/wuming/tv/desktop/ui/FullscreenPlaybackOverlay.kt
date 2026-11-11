package com.wuming.tv.desktop.ui

import com.wuming.tv.desktop.player.PlaybackState
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Window
import javax.swing.*

/** A separate native overlay keeps the heavyweight video surface full-size underneath. */
internal class FullscreenPlaybackOverlay(
    private val owner: Window,
    private val togglePause: () -> Unit,
    private val seekRelative: (Double) -> Unit,
    private val seekTo: (Double) -> Unit,
    private val exitFullscreen: () -> Unit,
) : AutoCloseable {
    private val window = JWindow(owner).apply { focusableWindowState = false }
    private val pause = JButton("Pause")
    private val timeline = JSlider(0, 1000, 0)
    private val elapsed = JLabel()
    private var state = PlaybackState()
    private var updating = false
    private var previousPointer = pointer()
    private var lastInteraction = System.nanoTime()
    internal val controlsVisible: Boolean get() = window.isVisible
    private val timer = Timer(100) { updateVisibility() }

    init {
        val content = JPanel(BorderLayout(8, 6)).apply {
            background = DesktopTvTheme.SURFACE
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(DesktopTvTheme.DIVIDER),
                BorderFactory.createEmptyBorder(8, 12, 8, 12),
            )
            add(JPanel(BorderLayout(8, 0)).apply {
                isOpaque = false
                add(timeline, BorderLayout.CENTER)
                add(elapsed, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(JPanel(FlowLayout(FlowLayout.CENTER, 8, 0)).apply {
                isOpaque = false
                fun button(text: String, action: () -> Unit) = JButton(text).apply {
                    isFocusable = false
                    addActionListener { action() }
                }
                add(button("−10s") { seekRelative(-10.0) })
                add(pause.apply {
                    isFocusable = false
                    putClientProperty("style", "primary")
                    addActionListener { togglePause() }
                })
                add(button("+10s") { seekRelative(10.0) })
                add(button("Exit fullscreen") { exitFullscreen() })
            }, BorderLayout.CENTER)
        }
        timeline.accessibleContext.accessibleName = "Fullscreen playback position"
        timeline.addChangeListener {
            if (!updating && !timeline.valueIsAdjusting && state.durationSeconds > 0)
                seekTo(state.durationSeconds * timeline.value / 1000.0)
        }
        window.contentPane = content
        DesktopTvTheme.scaleExplicitMetrics(content)
        timer.start()
    }

    fun updateState(snapshot: PlaybackState) {
        state = snapshot
        updating = true
        try {
            pause.text = if (snapshot.paused) "Play" else "Pause"
            timeline.isEnabled = snapshot.durationSeconds > 0
            if (!timeline.valueIsAdjusting) timeline.value = if (snapshot.durationSeconds > 0)
                (snapshot.positionSeconds / snapshot.durationSeconds * 1000).toInt().coerceIn(0, 1000) else 0
            elapsed.text = "${time(snapshot.positionSeconds)} / ${time(snapshot.durationSeconds)}"
        } finally { updating = false }
    }

    private fun updateVisibility() {
        if (!owner.isShowing) { window.isVisible = false; return }
        val point = pointer() ?: return
        val moved = point != previousPointer
        previousPointer = point
        if (moved && owner.bounds.contains(point) && owner.isActive) {
            lastInteraction = System.nanoTime()
            val width = minOf(DesktopTvTheme.px(720), owner.width - DesktopTvTheme.px(32)).coerceAtLeast(1)
            window.size = Dimension(width, window.preferredSize.height)
            window.setLocation(owner.x + (owner.width - width) / 2,
                owner.y + owner.height - window.height - DesktopTvTheme.px(20))
            window.isVisible = true
        }
        // Keep controls available while the user is hovering or dragging the timeline.
        if (window.isVisible && (window.bounds.contains(point) || timeline.valueIsAdjusting)) {
            lastInteraction = System.nanoTime()
        }
        if (!owner.isActive || System.nanoTime() - lastInteraction >= 2_200_000_000L) window.isVisible = false
    }

    override fun close() { timer.stop(); window.dispose() }
    private fun pointer(): Point? = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()
    private fun time(value: Double): String {
        val seconds = value.toLong().coerceAtLeast(0)
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }
}
