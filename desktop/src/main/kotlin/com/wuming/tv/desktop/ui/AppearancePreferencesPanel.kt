package com.wuming.tv.desktop.ui

import com.wuming.tv.core.design.Appearance
import com.wuming.tv.core.design.ColorTheme
import com.wuming.tv.core.design.DesignTokens
import com.wuming.tv.core.design.LayoutPolicy
import com.wuming.tv.desktop.config.DesktopAppearanceSettings
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.*

/** Changes are applied by the desktop appearance monitor without restarting playback. */
class AppearancePreferencesPanel(
    private val settings: DesktopAppearanceSettings = DesktopAppearanceSettings(),
    private val application: com.wuming.tv.desktop.config.DesktopApplicationSettings = com.wuming.tv.desktop.config.DesktopApplicationSettings(),
) : JPanel(BorderLayout()) {
    init {
        isOpaque = false
        val form = JPanel(GridBagLayout()).apply { isOpaque = false }
        val colors = JComboBox(arrayOf("Classic", "Aurora")).apply {
            selectedIndex = settings.colorTheme.id
            accessibleContext.accessibleName = "Color theme"
            addActionListener { settings.colorTheme = ColorTheme.fromId(selectedIndex) }
        }
        val appearance = JComboBox(arrayOf("System", "Light", "Dark")).apply {
            selectedIndex = settings.appearance.id
            accessibleContext.accessibleName = "Appearance"
            addActionListener { settings.appearance = Appearance.fromId(selectedIndex) }
        }
        listOf("Color theme" to colors, "Appearance" to appearance).forEachIndexed { row, (name, control) ->
            form.add(JLabel(name).apply { labelFor = control }, GridBagConstraints().apply {
                gridx = 0; gridy = row; anchor = GridBagConstraints.LINE_START
                insets = Insets(DesignTokens.SPACE_SMALL, 0, DesignTokens.SPACE_SMALL, DesignTokens.SPACE_LARGE)
            })
            form.add(control, GridBagConstraints().apply {
                gridx = 1; gridy = row; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
                insets = Insets(DesignTokens.SPACE_SMALL, 0, DesignTokens.SPACE_SMALL, 0)
            })
        }
        val singleInstance = JCheckBox("Prevent multiple app instances", application.preventMultipleInstances).apply {
            isOpaque = false
            toolTipText = "Applies to future launches. Close existing app instances after changing this option."
            addActionListener { application.preventMultipleInstances = isSelected }
        }
        form.add(singleInstance, GridBagConstraints().apply {
            gridx = 0; gridy = 2; gridwidth = 2; anchor = GridBagConstraints.LINE_START
            insets = Insets(DesignTokens.SPACE_MEDIUM, 0, DesignTokens.SPACE_SMALL, 0)
        })
        val content = JPanel(BorderLayout(0, DesignTokens.SPACE_MEDIUM)).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(DesignTokens.SPACE_LARGE, DesignTokens.SPACE_LARGE,
                DesignTokens.SPACE_LARGE, DesignTokens.SPACE_LARGE)
            add(eyebrow("Appearance"), BorderLayout.NORTH)
            add(form, BorderLayout.CENTER)
            add(mutedLabel("Saved automatically. System follows the Linux desktop theme."), BorderLayout.SOUTH)
        }
        add(JScrollPane(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(content, BorderLayout.NORTH)
        }).apply { border = null }, BorderLayout.CENTER)
    }

    override fun getPreferredSize() = Dimension(LayoutPolicy.formWidth(720), 240)
}
