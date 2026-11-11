package com.wuming.tv.desktop.ui

import java.awt.Dimension
import javax.swing.JPanel
import javax.swing.JScrollPane

/** One horizontal chip row, sized from the actual controls rather than a fixed text band. */
internal class DesktopChipScrollPane(private val row: JPanel) : JScrollPane(
    row, VERTICAL_SCROLLBAR_NEVER, HORIZONTAL_SCROLLBAR_AS_NEEDED,
) {
    override fun getPreferredSize(): Dimension {
        val padding = insets
        val viewportPadding = viewportBorder?.getBorderInsets(this)
        // Reserve scrollbar space even when the choices fit, keeping the row stable on resize.
        val height = row.preferredSize.height + padding.top + padding.bottom +
            (viewportPadding?.top ?: 0) + (viewportPadding?.bottom ?: 0) + horizontalScrollBar.preferredSize.height
        return Dimension(100, height)
    }

    override fun getMinimumSize(): Dimension = Dimension(0, preferredSize.height)
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}
