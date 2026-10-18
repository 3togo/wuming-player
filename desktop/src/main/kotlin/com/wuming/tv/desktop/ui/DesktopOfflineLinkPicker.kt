package com.wuming.tv.desktop.ui

import com.wuming.tv.core.source.config.OfflineLinkCatalog
import com.wuming.tv.core.source.config.ProtectedSourceUrl
import com.wuming.tv.core.source.config.SourceBrowser
import com.wuming.tv.core.source.config.SourceBrowserEntry
import com.wuming.tv.core.source.config.SourceKind
import com.wuming.tv.core.source.config.SourceOrigin
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

internal object DesktopOfflineLinkPicker {
    fun choose(parent: Component, kind: SourceKind,
               curated: List<SourceBrowserEntry>): SourceBrowserEntry? {
        val bytes = javaClass.getResourceAsStream("/link_catalog.json")?.use { it.readBytes() }
            ?: throw IllegalStateException("Bundled link catalog is missing.")
        val screened = OfflineLinkCatalog.parse(bytes).filter { it.kind == kind.name.lowercase() }
            .flatMap { group -> group.entries.map { entry ->
                SourceBrowserEntry(entry.name, entry.url, kind, SourceOrigin.SCREENED, entry.warehouse)
            } }
        val entries = SourceBrowser.merge(curated, screened)
        if (entries.isEmpty()) throw IllegalStateException("No sources are available.")

        val search = JTextField(28)
        val origin = JComboBox(arrayOf("All", "Curated", "Screened", "Local"))
        val model = DefaultListModel<SourceBrowserEntry>()
        val list = JList(model).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int,
                                                          selected: Boolean, focus: Boolean): Component {
                    val component = super.getListCellRendererComponent(list, value, index, selected, focus)
                    val entry = value as? SourceBrowserEntry
                    if (entry != null) text = "${entry.name}  ·  ${if (ProtectedSourceUrl.hasCredential(entry.url)) "URL hidden" else entry.host.ifBlank { "Local" }}  ·  " +
                        when (entry.origin) {
                            SourceOrigin.SCREENED -> "Screened"
                            SourceOrigin.LOCAL -> "Local"
                            SourceOrigin.CURATED -> "Curated"
                        }
                    return component
                }
            }
        }
        fun refresh() {
            val selectedUrl = list.selectedValue?.url
            val choice = when (origin.selectedIndex) {
                1 -> SourceOrigin.CURATED
                2 -> SourceOrigin.SCREENED
                3 -> SourceOrigin.LOCAL
                else -> null
            }
            val visible = SourceBrowser.filter(entries, search.text, kind, choice)
            model.clear()
            visible.forEach(model::addElement)
            list.selectedIndex = visible.indexOfFirst { it.url == selectedUrl }.takeIf { it >= 0 }
                ?: if (visible.isEmpty()) -1 else 0
        }
        search.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = refresh()
            override fun removeUpdate(event: DocumentEvent) = refresh()
            override fun changedUpdate(event: DocumentEvent) = refresh()
        })
        origin.addActionListener { refresh() }
        refresh()
        val panel = JPanel(BorderLayout(6, 6)).apply {
            add(JPanel(BorderLayout(6, 0)).apply {
                add(search, BorderLayout.CENTER)
                add(origin, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(JScrollPane(list).apply { preferredSize = Dimension(640, 360) }, BorderLayout.CENTER)
        }
        val result = JOptionPane.showConfirmDialog(parent, panel,
            "Browse ${kind.name.lowercase()} sources · screened links have not been playback tested",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
        return if (result == JOptionPane.OK_OPTION) list.selectedValue else null
    }
}
