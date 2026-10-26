package com.wuming.tv.desktop.ui

import com.wuming.tv.desktop.config.DesktopConfigurationUrlStore
import com.wuming.tv.desktop.config.DesktopDepot
import com.wuming.tv.desktop.config.DesktopTvBoxConfigurationLoader
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.prefs.Preferences
import javax.swing.SwingUtilities
import org.junit.Assert.*
import org.junit.Test

class ConfigurationStartupTest {
    private val json = """{"sites":[{"key":"demo","name":"Demo","type":1,"api":"https://example.test/api"}]}""".toByteArray()

    @Test fun changedBundleOffersReviewWithoutExecutingOrSilentlyUpdatingApproval() {
        val preferences = Preferences.userRoot().node("tvbox-desktop-test/${UUID.randomUUID()}")
        val artifact = java.nio.file.Files.createTempFile("changed-provider-", ".jar")
        java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(artifact)).use {
            it.putNextEntry(java.util.jar.JarEntry("marker.txt"))
            it.write("updated bundle".toByteArray())
            it.closeEntry()
        }
        val payload = """{"spider":"${artifact.toUri()}","sites":[
            {"key":"changed","name":"Changed","type":3,"api":"csp_Unknown"}
        ]}""".toByteArray()
        val loader = DesktopTvBoxConfigurationLoader(cache = null) { payload }
        val spec = loader.load("https://custom.test/tv").sites.single().jvmExecution!!
        val trustStore = com.wuming.tv.desktop.jvmspider.GuardedSourceTrustStore(preferences)
        val oldDigest = "a".repeat(64)
        trustStore.remember(spec, oldDigest, com.wuming.tv.core.runtime.guardspider.GuardedSpiderAbi.ARMEABI_V7A)
        DesktopConfigurationUrlStore(preferences).rememberWorking("https://custom.test/tv")
        val activated = AtomicInteger()
        val visibleStatus = java.util.concurrent.atomic.AtomicReference("")
        lateinit var panel: ConfigurationPanel
        try {
            SwingUtilities.invokeAndWait {
                panel = ConfigurationPanel(loader = loader, preferences = preferences,
                    onGuardedEnabled = { activated.incrementAndGet() },
                    onStartupStatus = { visibleStatus.set(it) })
                panel.loadSavedConfiguration()
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var visible = false
            while (!visible && System.nanoTime() < deadline) {
                SwingUtilities.invokeAndWait {
                    val button = panel.javaClass.getDeclaredField("reviewUpdated").apply { isAccessible = true }
                        .get(panel) as javax.swing.JButton
                    visible = button.isVisible && button.isEnabled
                }
                if (!visible) Thread.sleep(10)
            }
            assertTrue("A stale approval needs an accessible review action", visible)
            assertEquals(0, activated.get())
            assertEquals(oldDigest, trustStore.find(spec)?.sha256)
            assertTrue("The catalog must explain why the provider is waiting: ${visibleStatus.get()}",
                visibleStatus.get().contains("Review updated bundle"))
            SwingUtilities.invokeAndWait {
                val list = panel.javaClass.getDeclaredField("sites").apply { isAccessible = true }.get(panel) as javax.swing.JList<*>
                assertTrue(list.model.getElementAt(0).toString().contains("bundle updated"))
                assertTrue(list.model.getElementAt(0).toString().contains("review approval required"))
            }
        } finally {
            SwingUtilities.invokeAndWait { panel.cancelLoading() }
            preferences.removeNode()
            java.nio.file.Files.deleteIfExists(artifact)
        }
    }

    @Test fun runtimeSupportAndChecksDoNotDependOnCatalogueVisibility() {
        val preferences = Preferences.userRoot().node("tvbox-desktop-test/${UUID.randomUUID()}")
        val loaded = CountDownLatch(1)
        val payload = """{"spider":"https://example.test/spider.jar","sites":[
            {"key":"music","name":"Music","type":3,"api":"csp_MusicGuard","searchable":0},
            {"key":"education","name":"Education","type":3,"api":"csp_BiliGuard","searchable":0},
            {"key":"unknown","name":"Unknown","type":3,"api":"csp_Unknown","searchable":0},
            {"key":"invalid","name":"Invalid","type":3,"api":"csp_Invalid/Name","searchable":0}
        ]}""".toByteArray()
        lateinit var panel: ConfigurationPanel
        try {
            DesktopConfigurationUrlStore(preferences).rememberWorking("https://custom.test/tv")
            SwingUtilities.invokeAndWait {
                panel = ConfigurationPanel(
                    loader = DesktopTvBoxConfigurationLoader(cache = null) { payload },
                    preferences = preferences,
                    onLoaded = { loaded.countDown() },
                )
                panel.loadSavedConfiguration()
            }
            assertTrue(loaded.await(10, TimeUnit.SECONDS))
            SwingUtilities.invokeAndWait {
                fun components(root: java.awt.Container): List<java.awt.Component> =
                    root.components.flatMap { listOf(it) + if (it is java.awt.Container) components(it) else emptyList() }
                val components = components(panel)
                val sites = components.filterIsInstance<javax.swing.JList<*>>().single { it.model.size == 4 }
                val check = components.filterIsInstance<javax.swing.JButton>().single { it.text == "Check source…" }
                for (index in 0..2) {
                    sites.selectedIndex = index
                    val row = sites.model.getElementAt(index).toString()
                    assertTrue(row.contains("desktop spider adapter available"))
                    assertTrue(row.contains("browse-only · not searchable"))
                    assertFalse(row.contains("spider runtime required"))
                    assertTrue(check.isEnabled)
                }
                assertTrue(sites.model.getElementAt(0).toString().contains("verified desktop"))
                assertTrue(sites.model.getElementAt(1).toString().contains("verified desktop catalogue"))
                assertTrue(sites.model.getElementAt(2).toString().contains("unverified desktop"))
                sites.selectedIndex = 3
                assertTrue(sites.model.getElementAt(3).toString().contains("no supported desktop spider adapter"))
                assertFalse(check.isEnabled)
                assertFalse(preferences.keys().any { it.startsWith("guardedTrust.") })
            }
        } finally {
            SwingUtilities.invokeAndWait { panel.cancelLoading() }
            preferences.removeNode()
            preferences.flush()
        }
    }

    @Test fun unknownSpiderCanBeCheckedWithoutBeingOnTheAllowList() {
        val preferences = Preferences.userRoot().node("tvbox-desktop-test/${UUID.randomUUID()}")
        val loaded = CountDownLatch(1)
        val requested = java.util.concurrent.CopyOnWriteArrayList<String>()
        val unknown = """{"spider":"https://example.test/spider.jar","sites":[{"key":"new","name":"New","type":3,"api":"csp_Unknown"}]}""".toByteArray()
        lateinit var panel: ConfigurationPanel
        try {
            DesktopConfigurationUrlStore(preferences).rememberWorking("https://custom.test/tv")
            SwingUtilities.invokeAndWait {
                panel = ConfigurationPanel(
                    loader = DesktopTvBoxConfigurationLoader(cache = null) { url -> requested += url; unknown },
                    preferences = preferences,
                    onLoaded = { loaded.countDown() },
                )
                panel.loadSavedConfiguration()
            }
            assertTrue(loaded.await(10, TimeUnit.SECONDS))
            SwingUtilities.invokeAndWait {
                fun components(root: java.awt.Container): List<java.awt.Component> =
                    root.components.flatMap { listOf(it) + if (it is java.awt.Container) components(it) else emptyList() }
                val buttons = components(panel).filterIsInstance<javax.swing.JButton>()
                assertTrue(buttons.single { it.text == "Check source…" }.isEnabled)
                assertFalse(buttons.single { it.text == "Review source trust…" }.isEnabled)
                val rows = components(panel).filterIsInstance<javax.swing.JList<*>>()
                    .flatMap { list -> (0 until list.model.size).map { list.model.getElementAt(it).toString() } }
                assertTrue(rows.any { it.contains("desktop spider adapter available") && it.contains("unverified desktop") })
            }
            assertEquals(1, requested.size) // Loading an unknown site must not execute or approve its JAR.
            assertFalse(preferences.keys().any { it.startsWith("guardedTrust.") })
        } finally {
            SwingUtilities.invokeAndWait { panel.cancelLoading() }
            preferences.removeNode()
            preferences.flush()
        }
    }

    @Test fun startupWithMissingApprovalsOpensSetupOnceWithoutExecutingProviders() {
        val preferences = Preferences.userRoot().node("tvbox-desktop-test/${UUID.randomUUID()}")
        val prompted = CountDownLatch(1)
        val prompts = AtomicInteger()
        val requested = java.util.concurrent.CopyOnWriteArrayList<String>()
        val guardedJson = """{"spider":"https://example.test/spider.jar","sites":[{"key":"cz","name":"Cz","type":3,"api":"csp_NewCzGuard"}]}""".toByteArray()
        lateinit var panel: ConfigurationPanel
        try {
            DesktopConfigurationUrlStore(preferences).rememberWorking("https://custom.test/tv")
            SwingUtilities.invokeAndWait {
                panel = ConfigurationPanel(
                    loader = DesktopTvBoxConfigurationLoader(cache = null) { url -> requested += url; guardedJson },
                    preferences = preferences,
                    onApprovalRequired = { prompts.incrementAndGet(); prompted.countDown() },
                )
                panel.loadSavedConfiguration()
                panel.loadSavedConfiguration()
            }
            assertTrue("Missing approvals must surface setup instead of silently showing two sources", prompted.await(10, TimeUnit.SECONDS))
            assertEquals(1, prompts.get())
            assertEquals(1, requested.size)
            assertFalse(preferences.keys().any { it.startsWith("guardedTrust.") })
        } finally {
            SwingUtilities.invokeAndWait { panel.cancelLoading() }
            preferences.removeNode()
            preferences.flush()
        }
    }

    @Test fun firstStartupAutomaticallyLoadsDefaultAndRetriesTemporaryFailure() {
        val preferences = Preferences.userRoot().node("tvbox-desktop-test/${UUID.randomUUID()}")
        val loaded = CountDownLatch(1)
        val attempts = AtomicInteger()
        val requested = java.util.concurrent.CopyOnWriteArrayList<String>()
        val notices = java.util.concurrent.CopyOnWriteArrayList<String>()
        val depot = DesktopDepot(listOf(com.wuming.tv.desktop.config.DesktopDepotSource(
            "饭太硬", "https://preferred.test/config")), emptyList())
        lateinit var panel: ConfigurationPanel
        try {
            SwingUtilities.invokeAndWait {
                panel = ConfigurationPanel(
                    loader = DesktopTvBoxConfigurationLoader(cache = null) { url ->
                        requested += url
                        if (attempts.incrementAndGet() == 1) throw IOException("temporary startup outage")
                        json
                    },
                    preferences = preferences,
                    depot = depot,
                    onLoaded = { loaded.countDown() },
                    onStartupStatus = { notices += it },
                    startupRetryDelaysMillis = listOf(5, 5),
                )
                panel.loadSavedConfiguration()
                panel.loadSavedConfiguration()
            }
            assertTrue("Startup should recover without a Load click", loaded.await(10, TimeUnit.SECONDS))
            assertEquals(2, attempts.get())
            val default = depot.vod.single().location
            assertEquals(listOf(default, default), requested)
            assertEquals(default, DesktopConfigurationUrlStore(preferences).lastWorkingOr(""))
            assertTrue(notices.any { it.contains("retrying automatically") })
        } finally {
            SwingUtilities.invokeAndWait { panel.cancelLoading() }
            preferences.removeNode()
            preferences.flush()
        }
    }

    @Test fun removedDefaultWaitsForSelectionWithoutContactingAnotherSource() {
        val preferences = Preferences.userRoot().node("tvbox-desktop-test/${UUID.randomUUID()}")
        val attempts = AtomicInteger()
        lateinit var panel: ConfigurationPanel
        try {
            SwingUtilities.invokeAndWait {
                panel = ConfigurationPanel(
                    loader = DesktopTvBoxConfigurationLoader(cache = null) {
                        attempts.incrementAndGet()
                        json
                    },
                    preferences = preferences,
                    depot = DesktopDepot(listOf(com.wuming.tv.desktop.config.DesktopDepotSource(
                        "Other", "https://other.test/config")), emptyList()),
                )
                panel.loadSavedConfiguration()
                panel.loadSavedConfiguration()
            }
            assertEquals(0, attempts.get())
            assertEquals("", DesktopConfigurationUrlStore(preferences).lastWorkingOr(""))
        } finally {
            SwingUtilities.invokeAndWait { panel.cancelLoading() }
            preferences.removeNode()
            preferences.flush()
        }
    }

    @Test fun laterStartupLoadsSavedCustomUrl() {
        val preferences = Preferences.userRoot().node("tvbox-desktop-test/${UUID.randomUUID()}")
        val loaded = CountDownLatch(1)
        val requested = java.util.concurrent.CopyOnWriteArrayList<String>()
        lateinit var panel: ConfigurationPanel
        try {
            DesktopConfigurationUrlStore(preferences).rememberWorking("https://custom.test/tv")
            SwingUtilities.invokeAndWait {
                panel = ConfigurationPanel(
                    loader = DesktopTvBoxConfigurationLoader(cache = null) { url -> requested += url; json },
                    preferences = preferences,
                    onLoaded = { loaded.countDown() },
                )
                panel.loadSavedConfiguration()
            }
            assertTrue(loaded.await(10, TimeUnit.SECONDS))
            assertEquals(listOf("https://custom.test/tv"), requested)
        } finally {
            SwingUtilities.invokeAndWait { panel.cancelLoading() }
            preferences.removeNode()
            preferences.flush()
        }
    }
}
