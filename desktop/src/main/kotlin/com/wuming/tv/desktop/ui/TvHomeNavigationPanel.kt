package com.wuming.tv.desktop.ui

import com.wuming.tv.core.media.TvHomeAction
import com.wuming.tv.core.media.TvHomeLayoutSpec
import java.awt.BasicStroke
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JToggleButton

/** Swing rendering of Leanback's focusable left-side home navigation rail. */
class TvHomeNavigationPanel(
    private val spec: TvHomeLayoutSpec = TvHomeLayoutSpec.desktop(),
    onSelected: (TvHomeAction) -> Unit,
) : JPanel() {
    private val buttons = linkedMapOf<TvHomeAction, JToggleButton>()
    private val group = ButtonGroup()

    init {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createEmptyBorder(24, 12, 16, 12)
        preferredSize = Dimension(NAVIGATION_WIDTH, 0)

        // Brand section with logo
        add(JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createEmptyBorder(0, 12, 16, 0)
            alignmentX = LEFT_ALIGNMENT
            add(JLabel(DesktopAppIcon.icon(DesktopTvTheme.px(20))))
            add(Box.createHorizontalStrut(10))
            add(JLabel("無名").apply {
                font = font.deriveFont(Font.BOLD, 15f)
                foreground = DesktopTvTheme.TEXT
                alignmentX = LEFT_ALIGNMENT
            })
        })

        add(JLabel("EXPLORE").apply {
            foreground = DesktopTvTheme.ACCENT
            font = font.deriveFont(Font.BOLD, 10f)
            border = BorderFactory.createEmptyBorder(0, 14, 10, 0)
            alignmentX = LEFT_ALIGNMENT
        })

        spec.actions.forEach { action ->
            val button = JToggleButton(action.tileText()).apply {
                ui = DesktopButtonUI()
                preferredSize = Dimension(ACTION_TILE_WIDTH, ACTION_TILE_HEIGHT)
                minimumSize = Dimension(0, ACTION_TILE_HEIGHT)
                maximumSize = Dimension(Int.MAX_VALUE, ACTION_TILE_HEIGHT)
                alignmentX = LEFT_ALIGNMENT
                font = font.deriveFont(Font.BOLD, 13f)
                foreground = DesktopTvTheme.TEXT
                background = DesktopTvTheme.TILE
                isOpaque = false
                isFocusPainted = false
                border = BorderFactory.createEmptyBorder(10, 16, 10, 16)
                horizontalAlignment = javax.swing.SwingConstants.LEFT
                toolTipText = action.description()
                accessibleContext.accessibleName = action.displayName()
                addFocusListener(object : FocusAdapter() {
                    override fun focusGained(event: FocusEvent) { repaint() }
                    override fun focusLost(event: FocusEvent) { repaint() }
                })
                addActionListener { onSelected(action) }
            }
            group.add(button)
            buttons[action] = button
            add(button)
            add(Box.createVerticalStrut(6))
        }
        add(Box.createVerticalGlue())

        // Footer with keyboard shortcuts
        add(JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(12, 14, 4, 0)
            alignmentX = LEFT_ALIGNMENT
            add(JLabel("KEYBOARD SHORTCUTS").apply {
                foreground = DesktopTvTheme.SUBTLE_TEXT
                font = font.deriveFont(Font.BOLD, 9f)
                border = BorderFactory.createEmptyBorder(0, 0, 6, 0)
            })
            add(JLabel("ESC  Back    F11  Fullscreen").apply {
                foreground = DesktopTvTheme.MUTED_TEXT
                font = font.deriveFont(Font.PLAIN, 10f)
                border = BorderFactory.createEmptyBorder(0, 0, 2, 0)
            })
        })
    }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        // Draw subtle gradient background
        val gradientStart = DesktopTvTheme.SURFACE
        val gradientEnd = java.awt.Color(
            Math.max(0, gradientStart.red - 8),
            Math.max(0, gradientStart.green - 8),
            Math.max(0, gradientStart.blue - 8),
        )
        g.paint = java.awt.GradientPaint(0f, 0f, gradientStart, width.toFloat(), 0f, gradientEnd)
        g.fillRoundRect(0, 0, width, height, 20, 20)

        // Draw subtle right border
        g.color = DesktopTvTheme.DIVIDER
        g.stroke = BasicStroke(1f)
        g.drawLine(width - 1, 0, width - 1, height)

        g.dispose()
        super.paintComponent(graphics)
    }

    fun showSelection(action: TvHomeAction?) {
        group.clearSelection()
        action?.let { buttons[it]?.isSelected = true }
        buttons.forEach { (candidate, button) ->
            if (candidate == action) {
                button.background = DesktopTvTheme.ACCENT_SOFT
                button.foreground = DesktopTvTheme.ACCENT
            } else {
                button.background = DesktopTvTheme.TILE
                button.foreground = DesktopTvTheme.TEXT
            }
        }
    }

    fun focusFirst() {
        buttons.values.firstOrNull()?.requestFocusInWindow()
    }

    fun setRailWidth(width: Int) {
        if (width <= 0 || preferredSize.width == width) return
        preferredSize = Dimension(width, preferredSize.height)
        revalidate()
    }

    private fun TvHomeAction.tileText(): String =
        "${symbol()}   ${displayName()}"

    private fun TvHomeAction.symbol(): String = when (this) {
        TvHomeAction.VOD -> "▶"
        TvHomeAction.LIVE_TV -> "▣"
        TvHomeAction.SEARCH -> "⌕"
        TvHomeAction.KEEP -> "★"
        TvHomeAction.PUSH -> "↗"
        TvHomeAction.SETTINGS -> "⚙"
        TvHomeAction.CLOUD_ACCOUNTS -> "☁"
    }

    private fun TvHomeAction.displayName(): String = when (this) {
        TvHomeAction.VOD -> "Discover"
        TvHomeAction.LIVE_TV -> "Live"
        TvHomeAction.SEARCH -> "Search"
        TvHomeAction.KEEP -> "My library"
        TvHomeAction.PUSH -> "Cast & open"
        TvHomeAction.SETTINGS -> "Settings"
        TvHomeAction.CLOUD_ACCOUNTS -> "Accounts"
    }

    private fun TvHomeAction.description(): String = when (this) {
        TvHomeAction.VOD -> "Open video on demand"
        TvHomeAction.LIVE_TV -> "Open live television"
        TvHomeAction.SEARCH -> "Search the VOD catalogue"
        TvHomeAction.KEEP -> "Open kept videos"
        TvHomeAction.PUSH -> "Cast videos, receive from Android, or open media"
        TvHomeAction.SETTINGS -> "Load and inspect TVBox configuration"
        TvHomeAction.CLOUD_ACCOUNTS -> "Manage cloud accounts"
    }

    private companion object {
        const val NAVIGATION_WIDTH = 214
        const val ACTION_TILE_WIDTH = 184
        const val ACTION_TILE_HEIGHT = 48
    }
}
