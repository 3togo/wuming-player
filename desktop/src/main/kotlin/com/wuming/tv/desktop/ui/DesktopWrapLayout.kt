package com.wuming.tv.desktop.ui

import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout

/** Flow rows report their wrapped height, so controls remain reachable in split panes. */
internal class DesktopWrapLayout(alignment: Int = LEFT, horizontalGap: Int = 6, verticalGap: Int = 4) :
    FlowLayout(alignment, horizontalGap, verticalGap) {
    private var laidOutWidth = -1

    override fun layoutContainer(target: Container) {
        super.layoutContainer(target)
        if (target.width != laidOutWidth) {
            laidOutWidth = target.width
            // BorderLayout/BoxLayout measured the old width before assigning the new one.
            // Invalidate their cached row height once the actual width is known.
            javax.swing.SwingUtilities.invokeLater {
                target.invalidate()
                (target as? javax.swing.JComponent)?.revalidate()
            }
        }
    }

    override fun preferredLayoutSize(target: Container): Dimension = synchronized(target.treeLock) {
        val insets = target.insets
        val available = (target.width.takeIf { it > 0 } ?: target.parent?.width?.takeIf { it > 0 }
            ?: Int.MAX_VALUE) - insets.left - insets.right - hgap * 2
        var rowWidth = 0
        var rowHeight = 0
        var width = 0
        var height = 0
        target.components.filter { it.isVisible }.forEach { component ->
            val size = component.preferredSize
            val gap = if (rowWidth > 0) hgap else 0
            if (rowWidth > 0 && rowWidth + gap + size.width > available) {
                width = maxOf(width, rowWidth)
                height += rowHeight + vgap
                rowWidth = 0
                rowHeight = 0
            }
            rowWidth += (if (rowWidth > 0) hgap else 0) + size.width
            rowHeight = maxOf(rowHeight, size.height)
        }
        Dimension(maxOf(width, rowWidth) + insets.left + insets.right + hgap * 2,
            height + rowHeight + insets.top + insets.bottom + vgap * 2)
    }

    override fun minimumLayoutSize(target: Container): Dimension = Dimension(
        target.components.filter { it.isVisible }.maxOfOrNull { it.minimumSize.width } ?: 0,
        preferredLayoutSize(target).height,
    )
}
