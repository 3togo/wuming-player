package com.wuming.tv.desktop.ui

import com.wuming.tv.core.catalog.CatalogDescriptionHtml
import java.awt.Font
import javax.swing.JEditorPane
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.StyleSheet

/** Theme-aware Swing HTML view; provider formatting is normalized by catalog-core. */
internal class CatalogDetailsPane : JEditorPane() {
    private val htmlKit = HTMLEditorKit().apply {
        val inherited = styleSheet
        styleSheet = StyleSheet().apply {
            addStyleSheet(inherited)
            addRule("body { margin: 0; }")
            addRule("h3 { margin-top: 0; margin-bottom: 8px; font-size: 115%; }")
            addRule("p { margin-top: 0; margin-bottom: 8px; }")
            addRule("ul, ol { margin-top: 0; margin-bottom: 8px; margin-left: 20px; }")
            addRule("blockquote { margin-left: 14px; margin-right: 0; }")
        }
    }

    init {
        isEditable = false
        putClientProperty(HONOR_DISPLAY_PROPERTIES, true)
        editorKit = htmlKit
        font = font.deriveFont(Font.PLAIN, 15f)
        foreground = DesktopTvTheme.TEXT
        background = DesktopTvTheme.SURFACE
        border = null
    }

    override fun getScrollableTracksViewportWidth(): Boolean = true

    fun showDescription(title: String, metadata: List<String>, synopsis: String) {
        val heading = CatalogDescriptionHtml.text(title)
        val summary = metadata.joinToString("<br>", transform = CatalogDescriptionHtml::text)
        setHtml("<h3>$heading</h3><p>$summary</p>${CatalogDescriptionHtml.fragment(synopsis)}")
    }

    fun showPlainText(value: String) {
        if (contentType != "text/plain") contentType = "text/plain"
        text = value
        caretPosition = 0
    }

    fun appendPlainText(value: String) {
        document.insertString(document.length, value, null)
    }

    private fun setHtml(body: String) {
        if (editorKit !== htmlKit) editorKit = htmlKit
        text = "<html><body>$body</body></html>"
        caretPosition = 0
    }
}
