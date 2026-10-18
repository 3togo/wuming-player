package com.wuming.tv.desktop.ui

import com.wuming.tv.desktop.config.DesktopPlaybackSettings
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.util.prefs.Preferences
import javax.swing.*

/** Desktop persistence/UI port for playback choices already offered by Android. */
internal class PlaybackPreferencesPanel(preferences: Preferences) : JPanel(BorderLayout(0, 12)) {
    private val settings = DesktopPlaybackSettings(preferences)
    init {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(26, 28, 28, 28)
        val content = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(eyebrow("Player behaviour").apply { alignmentX = LEFT_ALIGNMENT })
            add(Box.createVerticalStrut(7))
            add(JLabel("Playback preferences").apply {
                font = font.deriveFont(Font.BOLD, 26f)
                alignmentX = LEFT_ALIGNMENT
            })
            add(Box.createVerticalStrut(5))
            add(mutedLabel("Control how episodes start, continue, and open in your external player.").apply {
                alignmentX = LEFT_ALIGNMENT
            })
            add(Box.createVerticalStrut(20))
            fun toggle(title: String, detail: String, selected: Boolean, save: (Boolean) -> Unit) {
                add(preferenceRow(title, detail, JCheckBox("", selected).apply {
                    accessibleContext.accessibleName = title
                    addActionListener { save(isSelected) }
                }))
                add(Box.createVerticalStrut(10))
            }
            toggle("Start immediately", "Play the first episode when opening a title.", settings.autoPlayFirstEpisode) { settings.autoPlayFirstEpisode = it }
            toggle("Continue the series", "Play the next episode automatically when one finishes.", settings.autoPlayNextEpisode) { settings.autoPlayNextEpisode = it }
            toggle("Remember progress", "Resume from the last position in the same episode.", settings.restorePlaybackPosition) { settings.restorePlaybackPosition = it }
            toggle("Fullscreen player", "Open mpv in fullscreen when playback begins.", settings.fullscreenOnStart) { settings.fullscreenOnStart = it }
            add(preferenceRow("Default speed", "Used for new mpv playback sessions.",
                JComboBox(arrayOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 3.0)).apply {
                    selectedItem = settings.defaultSpeed
                    accessibleContext.accessibleName = "Default playback speed"
                    addActionListener { settings.defaultSpeed = selectedItem as Double }
                    preferredSize = Dimension(112, 38)
                }))
            add(Box.createVerticalStrut(16))
            add(JLabel("Changes apply to the next video  ·  Speed and fullscreen require mpv").apply {
                foreground = DesktopTvTheme.SUBTLE_TEXT
                font = font.deriveFont(Font.PLAIN, 12f)
                alignmentX = LEFT_ALIGNMENT
            })
        }
        add(JScrollPane(content).apply {
            border = null
            isOpaque = false
            viewport.isOpaque = false
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = 18
        }, BorderLayout.CENTER)
    }

    private fun preferenceRow(title: String, detail: String, control: JComponent): JPanel =
        DesktopCardPanel(DesktopTvTheme.SURFACE_RAISED, 18).apply {
            layout = BorderLayout(18, 0)
            maximumSize = Dimension(Int.MAX_VALUE, 76)
            alignmentX = LEFT_ALIGNMENT
            add(JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(JLabel(title).apply { font = font.deriveFont(Font.BOLD, 15f) })
                add(Box.createVerticalStrut(4))
                add(mutedLabel(detail).apply { font = font.deriveFont(Font.PLAIN, 12f) })
            }, BorderLayout.CENTER)
            add(control, BorderLayout.EAST)
        }
}
