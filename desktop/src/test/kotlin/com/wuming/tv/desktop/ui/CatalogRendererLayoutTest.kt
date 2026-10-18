package com.wuming.tv.desktop.ui

import com.wuming.tv.core.catalog.CatalogItem
import java.awt.Container
import java.util.prefs.Preferences
import javax.swing.*
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogRendererLayoutTest {
    @Test fun posterLeavesRoomForTitleMetadataAndFocusBorder() {
        val prefs = Preferences.userRoot().node("tvbox-renderer-test/${System.nanoTime()}")
        try {
            SwingUtilities.invokeAndWait {
                DesktopTvTheme.install()
                val panel = CatalogPanel(null, prefs)
                try {
                    @Suppress("UNCHECKED_CAST")
                    val list = CatalogPanel::class.java.getDeclaredField("results").let {
                        it.isAccessible = true
                        it.get(panel) as JList<CatalogItem>
                    }
                    for (focus in listOf(false, true)) {
                        val renderer = list.cellRenderer.getListCellRendererComponent(list,
                            CatalogItem("offline", "one", "A title", remarks = "2026 · Drama"), 0, true, focus) as JPanel
                        renderer.setSize(list.fixedCellWidth, list.fixedCellHeight)
                        fun layout(parent: Container) {
                            parent.doLayout()
                            parent.components.filterIsInstance<Container>().forEach(::layout)
                        }
                        layout(renderer)
                        val text = renderer.components.filterIsInstance<JPanel>().single()
                        for (label in text.components.filterIsInstance<JLabel>()) {
                            assertTrue("Text is fully visible with focus=$focus", label.height >= label.preferredSize.height)
                            assertTrue(label.y + label.height <= text.height)
                        }
                    }
                } finally { panel.close() }
            }
        } finally { prefs.removeNode() }
    }
}
