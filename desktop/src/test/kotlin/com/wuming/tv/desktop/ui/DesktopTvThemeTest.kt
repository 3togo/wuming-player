package com.wuming.tv.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.JScrollBar
import javax.swing.JComboBox
import javax.swing.UIManager

class DesktopTvThemeTest {
    @Test
    fun `standard buttons keep a shared minimum without clipping larger text`() {
        DesktopTvTheme.install()
        val button = javax.swing.JButton("Apply").apply { updateUI() }
        val minimum = DesktopTvTheme.px(com.wuming.tv.core.design.DesignTokens.CONTROL_MIN_HEIGHT)
        assertTrue(button.preferredSize.height >= minimum)
        button.font = button.font.deriveFont(80f)
        assertTrue(button.preferredSize.height > minimum)
        assertTrue(button.preferredSize.height >= button.getFontMetrics(button.font).height)
    }


    @Test
    fun `default controls consume shared semantic label size once`() {
        DesktopTvTheme.install()
        assertEquals(DesktopTvTheme.textSize(com.wuming.tv.core.design.TextRole.LABEL), UIManager.getFont("Label.font").size)
        assertTrue(DesktopTvTheme.textSize(com.wuming.tv.core.design.TextRole.HEADLINE) > UIManager.getFont("Label.font").size)
    }

    @Test
    fun `dark theme installs one scrollbar delegate for every scroll pane`() {
        DesktopTvTheme.install()
        val scrollbar = JScrollBar().apply { updateUI() }

        assertTrue(scrollbar.ui is DesktopDarkScrollBarUI)
        assertEquals(DesktopTvTheme.SURFACE, UIManager.getColor("ScrollBar.track"))
        assertEquals(DesktopTvTheme.TILE_SELECTED, UIManager.getColor("ScrollBar.thumb"))
    }

    @Test
    fun `combo box reserves vertical room for its rendered text`() {
        DesktopTvTheme.install()
        val combo = JComboBox(arrayOf("豆豆片单")).apply { updateUI() }
        val metrics = combo.getFontMetrics(combo.font)

        assertTrue(combo.ui is DesktopComboBoxUI)
        assertTrue(combo.preferredSize.height >= metrics.height + DesktopTvTheme.px(8))
    }
}
