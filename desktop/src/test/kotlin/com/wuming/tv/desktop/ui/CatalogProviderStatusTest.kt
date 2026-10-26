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
