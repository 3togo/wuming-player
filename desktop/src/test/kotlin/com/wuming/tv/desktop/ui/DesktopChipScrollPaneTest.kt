package com.wuming.tv.desktop.ui

import java.awt.FlowLayout
import java.awt.Font
import javax.swing.*
import org.junit.Assert.*
import org.junit.Test

class DesktopChipScrollPaneTest {
    @Test fun rowFitsCaptionsAndScrollbarWithDifferentFontSizesAndWidths() {
        SwingUtilities.invokeAndWait {
            DesktopTvTheme.install()
            val row = JPanel(FlowLayout(FlowLayout.LEFT, 6, 1))
            val pane = DesktopChipScrollPane(row).apply {
                border = BorderFactory.createEmptyBorder(4, 0, 2, 0)
            }
            for (fontSize in listOf(15f, 24f, 32f)) {
                row.removeAll()
                repeat(7) { row.add(JToggleButton("热播电影 $it").apply {
                    font = Font(Font.SANS_SERIF, Font.BOLD, fontSize.toInt())
                    border = BorderFactory.createEmptyBorder(9, 13, 9, 13)
                }) }
                row.invalidate()
                for (width in listOf(300, 2400, 300)) {
                    pane.setSize(width, pane.preferredSize.height)
                    pane.doLayout()
                    pane.viewport.doLayout()
                    row.doLayout()
                    assertEquals(pane.preferredSize.height, pane.minimumSize.height)
                    row.components.forEach { chip ->
                        assertTrue(chip.y >= 0)
                        assertTrue("Caption clips at font size $fontSize", chip.y + chip.height <= pane.viewport.height)
                    }
                }
            }
        }
    }
}
