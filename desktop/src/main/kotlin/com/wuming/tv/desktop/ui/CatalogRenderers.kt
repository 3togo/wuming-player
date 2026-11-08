package com.wuming.tv.desktop.ui

import com.wuming.tv.core.catalog.CatalogItem
import com.wuming.tv.core.catalog.CatalogItemKind
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import kotlin.math.roundToInt
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.ListCellRenderer
import javax.swing.UIManager

internal class CatalogItemRenderer(
    private val thumbnails: CatalogThumbnailCache,
    private val thumbnailReady: () -> Unit,
    private val sourceName: (String) -> String,
) : JPanel(BorderLayout(0, DesktopTvTheme.px(2))), ListCellRenderer<CatalogItem> {
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
        title.font = title.font.deriveFont(Font.BOLD, DesktopTvTheme.px(12).toFloat())
        metadata.font = metadata.font.deriveFont(DesktopTvTheme.px(10).toFloat())
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
        val gutter = DesktopTvTheme.px(3)
        border = if (focus) {
            BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(DesktopTvTheme.ACCENT),
                BorderFactory.createEmptyBorder(gutter - 1, gutter - 1, gutter - 1, gutter - 1),
            )
        } else {
            BorderFactory.createEmptyBorder(gutter, gutter, gutter, gutter)
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

    /** Reserve the captions in addition to a full-width poster, rather than shrinking art for text. */
    fun cellHeight(width: Int): Int {
        val gutter = DesktopTvTheme.px(3)
        val artworkWidth = (width - gutter * 2).coerceAtLeast(1)
        val artworkHeight = (artworkWidth.toDouble() * CatalogThumbnailCache.HEIGHT / CatalogThumbnailCache.WIDTH).roundToInt()
        val captions = title.getFontMetrics(title.font).height + metadata.getFontMetrics(metadata.font).height
        return artworkHeight + captions + gutter * 2 + (layout as BorderLayout).vgap
    }

    companion object {
        const val BASE_CARD_WIDTH = 180
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
internal class LeanbackChip(
    text: String,
    compact: Boolean = false,
) : JToggleButton(text) {

    init {
        putClientProperty("JComponent.sizeVariant", "small")
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
internal class NamedRenderer<T>(private val name: (T?) -> String) : javax.swing.DefaultListCellRenderer() {
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

internal class EpisodeRenderer : javax.swing.DefaultListCellRenderer() {
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

    fun cellWidth(viewportWidth: Int, scalePercent: Int): Int {
        val available = viewportWidth.coerceAtLeast(1)
        val preferred = (maxOf(DesktopTvTheme.px(96).toDouble(), available * BASE_VIEWPORT_FRACTION) *
            scalePercent / 100.0).coerceAtLeast(1.0)
        val columns = (available / preferred).toInt().coerceAtLeast(1)
        // Distribute the spare width into the cards instead of leaving a large trailing gutter.
        return (available / columns).coerceAtLeast(1)
    }
}
