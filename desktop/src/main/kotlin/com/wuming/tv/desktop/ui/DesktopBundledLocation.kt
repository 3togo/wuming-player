package com.wuming.tv.desktop.ui

import com.wuming.tv.core.source.config.ProtectedSourceUrl
import com.wuming.tv.core.source.config.SourceBrowserEntry
import com.wuming.tv.core.source.config.SourceEditGate
import java.awt.Component
import javax.swing.JOptionPane
import javax.swing.JPasswordField
import javax.swing.JTextField

/** Shows the chosen source by name while retaining its URL for the loader. */
internal class DesktopBundledLocation(private val field: JTextField) {
    private var lockedUrl: String? = null

    init {
        val initial = field.text.trim()
        if (ProtectedSourceUrl.hasCredential(initial)) lock(initial, ProtectedSourceUrl.HIDDEN_LABEL)
    }

    fun value(): String = lockedUrl ?: field.text.trim()

    fun select(entry: SourceBrowserEntry) = lock(entry.url, entry.name)

    fun select(url: String, name: String) = lock(url, name)

    fun manual(value: String) {
        lockedUrl = null
        field.isEditable = true
        field.text = value
    }

    fun unlock(parent: Component): Boolean {
        val url = lockedUrl ?: return true
        val password = JPasswordField(16)
        val response = JOptionPane.showConfirmDialog(parent, password, "Admin password to edit source URL",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
        val accepted = response == JOptionPane.OK_OPTION && SourceEditGate.accepts(String(password.password))
        password.password.fill('\u0000')
        if (!accepted) {
            if (response == JOptionPane.OK_OPTION) JOptionPane.showMessageDialog(parent, "Incorrect password.")
            return false
        }
        manual(url)
        field.requestFocusInWindow()
        return true
    }

    private fun lock(url: String, label: String) {
        lockedUrl = url
        field.text = label
        field.isEditable = false
    }
}
