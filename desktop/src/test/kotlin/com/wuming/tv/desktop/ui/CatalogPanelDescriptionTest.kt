package com.wuming.tv.desktop.ui

import com.wuming.tv.core.catalog.*
import java.util.prefs.Preferences
import javax.swing.*
import org.junit.Assert.*
import org.junit.Test

class CatalogPanelDescriptionTest {
    @Test fun loadedProviderSynopsisIsRenderedAsHtmlThroughTheCatalogue() {
        val prefs = Preferences.userRoot().node("catalogue-html-test/${System.nanoTime()}")
        prefs.putBoolean("playback.autoPlayFirstEpisode", false)
        val item = CatalogItem("html-source", "movie", "電影 & 故事")
        val source = object : CatalogSourceExecutor {
            override val source = CatalogSource("html-source", "Offline HTML")
            override fun browseHome() = CatalogHome(items = listOf(item))
            override fun search(query: String) = emptyList<CatalogItem>()
            override fun detail(id: String) = CatalogDetail(item = item, actor = "張三", director = "李四",
                content = "<p>第一段<br/>第二行</p><p><b>改編小說。</b></p>")
            override fun play(flag: String, id: String) = CatalogPlayback()
        }
        lateinit var panel: CatalogPanel
        var created = false
        try {
            SwingUtilities.invokeAndWait { panel = CatalogPanel(null, prefs); created = true; panel.addLocalExecutor(source) }
            waitFor {
                val results: JList<*> = field(panel, "results")
                results.model.size == 1
            }
            SwingUtilities.invokeAndWait {
                field<JList<*>>(panel, "results").selectedIndex = 0
                field<JButton>(panel, "loadDetails").doClick()
            }
            waitFor {
                val pane: CatalogDetailsPane = field(panel, "details")
                pane.document.getText(0, pane.document.length).contains("改編小說。")
            }
            SwingUtilities.invokeAndWait {
                val pane: CatalogDetailsPane = field(panel, "details")
                val text = pane.document.getText(0, pane.document.length)
                assertTrue(text.contains("電影 & 故事"))
                assertTrue(text.contains("Cast: 張三"))
                assertTrue(text.contains("Director: 李四"))
                assertFalse(text.contains("<br"))
                assertFalse(text.contains("</p>"))
            }
        } finally {
            SwingUtilities.invokeAndWait { if (created) panel.close() }
            prefs.removeNode()
        }
    }

    private fun waitFor(check: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (true) {
            var ready = false
            SwingUtilities.invokeAndWait { ready = check() }
            if (ready) return
            assertTrue("Catalogue description did not become ready", System.nanoTime() < deadline)
            Thread.sleep(20)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(target: Any, name: String): T = target.javaClass.getDeclaredField(name).let {
        it.isAccessible = true
        it.get(target) as T
    }
}
