package com.wuming.tv.desktop.ui

import com.wuming.tv.core.design.Appearance
import com.wuming.tv.core.design.ColorTheme
import org.junit.Assert.*
import org.junit.Test
import java.awt.Component
import java.awt.Container
import java.awt.GridLayout
import java.awt.image.BufferedImage
import javax.swing.*

class DesktopWidgetAppearanceTest {
    @Test fun `all desktop widget families render with the system palette`() = SwingUtilities.invokeAndWait {
        for (dark in listOf(true, false)) {
            DesktopTvTheme.install(appearance = Appearance.SYSTEM, systemDark = dark)
            val widgets = listOf<JComponent>(
                JLabel("愛情假說 / Label"), JButton("Play").apply { putClientProperty("style", "primary"); model.isRollover = true },
                JButton("Disabled").apply { isEnabled = false }, JToggleButton("Selected", true),
                JCheckBox("Private viewing", true), JRadioButton("Radio", true), JComboBox(arrayOf("Source", "Other")),
                JTextField("Search"), JPasswordField("Password"), JTextArea("Details"), JTextPane().apply { text = "Text pane" },
                JSpinner(SpinnerNumberModel(36, 1, 100, 1)), JSlider(50, 150, 100),
                JProgressBar().apply { value = 60; isStringPainted = true },
                JScrollPane(JList(arrayOf("Selected episode", "Other episode")).apply { selectedIndex = 0 }),
                JScrollPane(JTable(arrayOf(arrayOf<Any>("Saved copy", "Ready")), arrayOf("Name", "Status"))),
                JScrollPane(JTree()), JTabbedPane().apply { addTab("Settings", JPanel()); addTab("Accounts", JPanel()) },
                JSplitPane(JSplitPane.HORIZONTAL_SPLIT, JLabel("Left"), JLabel("Right")),
                JToolTip().apply { tipText = "Tooltip" },
                JMenuBar().apply { add(JMenu("Menu").apply { add(JMenuItem("Open")) }) },
                JPopupMenu().apply { add(JMenuItem("Action")); add(JCheckBoxMenuItem("Checked", true)) },
                JOptionPane("Confirm operation?", JOptionPane.QUESTION_MESSAGE, JOptionPane.YES_NO_OPTION), JFileChooser(),
            )
            assertTrue(widgets.filterIsInstance<JSlider>().single().ui is DesktopSliderUI)
            assertEquals(DesktopTvTheme.SURFACE, widgets.filterIsInstance<JPasswordField>().single().background)
            assertEquals(DesktopTvTheme.TEXT, widgets.filterIsInstance<JSpinner>().single().foreground)
            val gallery = JPanel(GridLayout(0, 3, 12, 12)).apply { widgets.forEach { widget ->
                add(JPanel(java.awt.BorderLayout()).apply {
                    add(widget, if (widget is JFileChooser || widget is JScrollPane || widget is JOptionPane)
                        java.awt.BorderLayout.CENTER else java.awt.BorderLayout.NORTH)
                })
            }; setSize(1500, 1800) }
            layout(gallery)
            val image = BufferedImage(gallery.width, gallery.height, BufferedImage.TYPE_INT_RGB)
            image.createGraphics().let { graphics -> gallery.printAll(graphics); graphics.dispose() }
            javax.imageio.ImageIO.write(image, "png", java.io.File("/tmp/tv-desktop-widgets-${if (dark) "dark" else "light"}.png"))
            // The slider's formerly native white strip must now use its themed background.
            val slider = widgets.filterIsInstance<JSlider>().single()
            val sliderImage = BufferedImage(slider.width, slider.height, BufferedImage.TYPE_INT_RGB)
            sliderImage.createGraphics().let { graphics -> slider.paint(graphics); graphics.dispose() }
            assertEquals(slider.background.rgb, sliderImage.getRGB(0, 0))
            val bar = JScrollBar().apply { setSize(16, 180); doLayout() }
            val barImage = BufferedImage(16, 180, BufferedImage.TYPE_INT_RGB)
            barImage.createGraphics().let { graphics -> bar.paint(graphics); graphics.dispose() }
            assertTrue((20 until 160).any { y -> barImage.getRGB(8, y) != DesktopTvTheme.SURFACE.rgb })
        }
    }

    @Test fun `live refresh recolors existing fields borders and custom cards without losing state`() = SwingUtilities.invokeAndWait {
        DesktopTvTheme.install(appearance = Appearance.LIGHT)
        val card = DesktopCardPanel()
        val field = JTextField("Keep my input").apply { caretPosition = 3 }
        val label = JLabel("Custom").apply { foreground = DesktopTvTheme.MUTED_TEXT }
        val accentText = JLabel("On accent").apply { foreground = DesktopTvTheme.ON_ACCENT }
        card.add(field); card.add(label); card.add(accentText)
        card.border = BorderFactory.createLineBorder(DesktopTvTheme.DIVIDER)
        DesktopTvTheme.refresh(ColorTheme.CLASSIC, Appearance.SYSTEM, true, listOf(card))
        assertEquals("Keep my input", field.text)
        assertEquals(3, field.caretPosition)
        assertEquals(DesktopTvTheme.SURFACE, field.background)
        assertEquals(DesktopTvTheme.TEXT, field.foreground)
        assertEquals(DesktopTvTheme.MUTED_TEXT, label.foreground)
        assertEquals(DesktopTvTheme.ON_ACCENT, accentText.foreground)
        assertEquals(DesktopTvTheme.DIVIDER, (card.border as javax.swing.border.LineBorder).lineColor)
        DesktopTvTheme.refresh(ColorTheme.AURORA, Appearance.LIGHT, false, listOf(card))
        assertEquals(DesktopTvTheme.MUTED_TEXT, label.foreground)
        assertEquals(DesktopTvTheme.SURFACE, field.background)
    }

    private fun layout(component: Component) {
        if (component is Container) { component.doLayout(); component.components.forEach(::layout) }
    }
}
