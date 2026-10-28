package com.wuming.tv.desktop.ui

import com.wuming.tv.desktop.config.DesktopTvBoxConfigurationLoader
import com.wuming.tv.desktop.library.DesktopUserLibrary
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.SwingUtilities
import org.junit.Assert.*
import org.junit.Test

class CatalogProviderStatusTest {
    @Test fun emptySearchResumesWhenAnotherProviderBecomesReady() {
        val directory = Files.createTempDirectory("provider-search-test-")
        val preferences = Preferences.userRoot().node("tvbox-desktop-test/${UUID.randomUUID()}")
        var panel: CatalogPanel? = null
        fun provider(key: String, withResult: Boolean) = object : com.wuming.tv.core.catalog.CatalogSourceExecutor {
            override val source = com.wuming.tv.core.catalog.CatalogSource(key, key)
            override fun search(query: String) = if (withResult)
                listOf(com.wuming.tv.core.catalog.CatalogItem(key, "movie", query)) else emptyList()
            override fun detail(id: String): com.wuming.tv.core.catalog.CatalogDetail = error("unused")
            override fun play(flag: String, id: String): com.wuming.tv.core.catalog.CatalogPlayback = error("unused")
        }
        fun field(name: String) = panel!!.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(panel)
        fun await(check: () -> Boolean) {
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                var done = false
                SwingUtilities.invokeAndWait { done = check() }
                if (done) return
                Thread.sleep(20)
            }
            error("UI did not reach expected state")
        }
        try {
            SwingUtilities.invokeAndWait {
                panel = CatalogPanel(null, preferences, userLibrary = DesktopUserLibrary.open(directory.resolve("library.json")))
                panel!!.addLocalExecutor(provider("first", false))
                panel!!.focusSearch()
                (field("sources") as javax.swing.JComboBox<*>).selectedIndex = 0
                (field("query") as javax.swing.JTextField).text = "Movie"
                panel!!.javaClass.getDeclaredMethod("search", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(panel, false)
            }
            await { (field("status") as JLabel).text.startsWith("Found 0 results") }
            SwingUtilities.invokeAndWait { panel!!.addLocalExecutors(listOf(provider("later", true)), preserveBrowse = true) }
            await { (field("results") as JList<*>).model.size == 1 }
        } finally {
            SwingUtilities.invokeAndWait { panel?.close() }
            preferences.removeNode()
            directory.toFile().deleteRecursively()
        }
    }

    @Test fun emptyConfiguredCatalogShowsPendingProviderApproval() {
        val directory = Files.createTempDirectory("provider-status-test-")
        val preferences = Preferences.userRoot().node("tvbox-desktop-test/${UUID.randomUUID()}")
        val json = """{"spider":"https://example.test/provider.jar","sites":[
            {"key":"index","name":"Index","type":3,"api":"csp_DouDouGuard"}
        ]}""".toByteArray()
        val configuration = DesktopTvBoxConfigurationLoader(cache = null) { json }.load("https://custom.test/tv")
        var panel: CatalogPanel? = null
        try {
            SwingUtilities.invokeAndWait {
                val catalog = CatalogPanel(null, preferences, userLibrary = DesktopUserLibrary.open(directory.resolve("library.json")))
                panel = catalog
                catalog.useLocalConfiguration(configuration)
                catalog.showConfigurationStatus("Provider approval needed. Settings → Sites → Review updated bundle.")
                val results = catalog.javaClass.getDeclaredField("results").apply { isAccessible = true }.get(catalog) as JList<*>
                val status = catalog.javaClass.getDeclaredField("status").apply { isAccessible = true }.get(catalog) as JLabel
                assertEquals(0, results.model.size)
                assertTrue("An empty configured catalog must show the provider blocker, but showed: ${status.text}",
                    status.text.contains("Review updated bundle"))
            }
        } finally {
            SwingUtilities.invokeAndWait { panel?.close() }
            preferences.removeNode()
            directory.toFile().deleteRecursively()
        }
    }
}
