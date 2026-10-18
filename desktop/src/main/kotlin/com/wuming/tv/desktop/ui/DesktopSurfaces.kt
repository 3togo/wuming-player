package com.wuming.tv.desktop.ui

import com.wuming.tv.core.design.DesignTokens
import java.awt.BasicStroke
import java.awt.Color
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel

/** Small desktop-only visual primitives shared by the window shell and preference screens. */
internal class DesktopGradientPanel : JPanel() {
    init { isOpaque = false }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.paint = GradientPaint(
            0f,
            0f,
            DesktopTvTheme.BACKGROUND_RAISED,
            width.toFloat(),
            height.toFloat(),
            DesktopTvTheme.BACKGROUND,
        )
        g.fillRect(0, 0, width, height)
        g.dispose()
        super.paintComponent(graphics)
    }
}

internal open class DesktopCardPanel(
    private var fill: Color = DesktopTvTheme.SURFACE,
    private val radius: Int = DesignTokens.CORNER_LARGE * 2,
    private val elevation: Int = 0,
) : JPanel() {
    init {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(DesignTokens.SPACE_LARGE, DesignTokens.SPACE_LARGE,
            DesignTokens.SPACE_LARGE, DesignTokens.SPACE_LARGE)
    }

    internal fun refreshFill(colors: Map<Color, Color>) { fill = colors[fill] ?: fill }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        // Draw shadow for elevated cards
        if (elevation > 0) {
            g.color = java.awt.Color(0, 0, 0, elevation * 15)
            g.fillRoundRect(
                elevation,
                elevation,
                width - elevation,
                height - elevation,
                radius,
                radius
            )
        }

        // Draw background
        g.color = fill
        g.fillRoundRect(0, 0, width - elevation, height - elevation, radius, radius)

        // Draw subtle border
        g.color = DesktopTvTheme.DIVIDER
        g.stroke = BasicStroke(1f)
        g.drawRoundRect(0, 0, (width - elevation - 1).coerceAtLeast(0), (height - elevation - 1).coerceAtLeast(0), radius, radius)

        g.dispose()
        super.paintComponent(graphics)
    }
}

internal fun eyebrow(text: String): JLabel = JLabel(text.uppercase()).apply {
    foreground = DesktopTvTheme.ACCENT
    font = font.deriveFont(java.awt.Font.BOLD, 11f)
}

internal fun mutedLabel(text: String): JLabel = JLabel(text).apply {
    foreground = DesktopTvTheme.MUTED_TEXT
}
