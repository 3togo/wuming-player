package com.wuming.tv.desktop.ui

import com.wuming.tv.core.design.Appearance
import com.wuming.tv.core.design.ColorTheme
import com.wuming.tv.core.design.ThemePolicy
import com.wuming.tv.desktop.config.DesktopAppearanceSettings
import java.awt.Color
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import javax.swing.*
import org.junit.Assert.*
import org.junit.Test

class AppearancePreferencesPanelTest {
    @Test fun appearanceControlsPersistAndRenderInAllPalettes() {
        val prefs = Preferences.userRoot().node("tvbox-appearance-test/${System.nanoTime()}")
        try {
            SwingUtilities.invokeAndWait {
                val settings = DesktopAppearanceSettings(prefs)
                assertEquals(Appearance.SYSTEM, settings.appearance)
                val application = com.wuming.tv.desktop.config.DesktopApplicationSettings(prefs.node("application"))
                assertTrue(application.preventMultipleInstances)
                val panel = AppearancePreferencesPanel(settings, application)
                fun checkboxes(parent: Container): List<JCheckBox> = parent.components.flatMap {
                    if (it is JCheckBox) listOf(it) else if (it is Container) checkboxes(it) else emptyList()
                }
                val singleInstance = checkboxes(panel).single { it.text == "Prevent multiple app instances" }
                assertTrue(singleInstance.isSelected)
                singleInstance.doClick()
                assertFalse(com.wuming.tv.desktop.config.DesktopApplicationSettings(prefs.node("application")).preventMultipleInstances)
                singleInstance.doClick()
                assertTrue(application.preventMultipleInstances)
                fun combos(parent: Container): List<JComboBox<*>> = parent.components.flatMap {
                    if (it is JComboBox<*>) listOf(it) else if (it is Container) combos(it) else emptyList()
                }
                val controls = combos(panel)
                controls.single { it.accessibleContext.accessibleName == "Color theme" }.selectedIndex = 1
                controls.single { it.accessibleContext.accessibleName == "Appearance" }.selectedIndex = 1
                assertEquals(ColorTheme.AURORA, DesktopAppearanceSettings(prefs).colorTheme)
                assertEquals(Appearance.LIGHT, DesktopAppearanceSettings(prefs).appearance)
                val appearance = controls.single { it.accessibleContext.accessibleName == "Appearance" }
                appearance.selectedIndex = 2
                assertEquals(Appearance.DARK, DesktopAppearanceSettings(prefs).appearance)
                appearance.selectedIndex = 0
                assertEquals(Appearance.SYSTEM, DesktopAppearanceSettings(prefs).appearance)
                assertTrue(controls.all { it.isFocusable })
                for (theme in ColorTheme.entries) for (mode in listOf(Appearance.DARK, Appearance.LIGHT)) {
                    DesktopTvTheme.install(theme, mode)
                    val p = ThemePolicy.palette(theme, mode, true)
                    assertEquals(Color(p.surface, true), UIManager.getColor("TextField.background"))
                    assertEquals(Color(p.text, true), UIManager.getColor("Label.foreground"))
                    for (width in listOf(360, 720)) {
                        val preview = JPanel(java.awt.BorderLayout()).apply {
                            add(AppearancePreferencesPanel(settings), java.awt.BorderLayout.CENTER)
                            add(JButton("Play / 播放").apply { putClientProperty("style", "primary") }, java.awt.BorderLayout.SOUTH)
                            setSize(width, 320)
                        }
                        fun layout(root: Container) { root.doLayout(); root.components.filterIsInstance<Container>().forEach(::layout) }
                        layout(preview)
                        assertTrue(combos(preview).all { it.width >= 100 && it.height >= it.getFontMetrics(it.font).height })
                        val image = BufferedImage(width, 320, BufferedImage.TYPE_INT_RGB)
                        image.createGraphics().let { g -> try { preview.printAll(g) } finally { g.dispose() } }
                        val target = File("build/reports/appearance/${theme.name}-${mode.name}-$width.png")
                        target.parentFile.mkdirs()
                        ImageIO.write(image, "png", target)
                    }
                }
            }
        } finally {
            SwingUtilities.invokeAndWait { DesktopTvTheme.install() }
            prefs.removeNode()
        }
    }
}
