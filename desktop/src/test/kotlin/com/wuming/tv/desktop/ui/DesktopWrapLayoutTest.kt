package com.wuming.tv.desktop.ui

import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingUtilities
import org.junit.Assert.*
import org.junit.Test

class DesktopWrapLayoutTest {
    @Test fun narrowControlRowsGrowAndReturnToOneRowWhenWidened() {
        SwingUtilities.invokeAndWait {
            val row = JPanel(DesktopWrapLayout()).apply {
                repeat(4) { add(JButton("Action $it").apply { preferredSize = Dimension(100, 36) }) }
            }
            for (width in listOf(240, 480, 240)) {
                row.setSize(width, 1)
                row.setSize(width, row.preferredSize.height)
                row.doLayout()
                row.components.forEach { control ->
                    assertTrue(control.x >= 0)
                    assertTrue(control.x + control.width <= row.width)
                    assertTrue(control.y + control.height <= row.height)
                }
                assertEquals(if (width == 240) 2 else 1, row.components.map { it.y }.distinct().size)
            }
        }
    }
}
