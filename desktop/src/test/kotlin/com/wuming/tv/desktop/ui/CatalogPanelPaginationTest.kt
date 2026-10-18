package com.wuming.tv.desktop.ui

import com.wuming.tv.core.catalog.*
import com.wuming.tv.desktop.config.DesktopCatalogSettings
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.prefs.Preferences
import javax.swing.*
import org.junit.Assert.*
import org.junit.Test

class CatalogPanelPaginationTest {
    private fun <T> field(panel: CatalogPanel, name: String): T {
        @Suppress("UNCHECKED_CAST")
        return panel.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(panel) as T
    }
    private fun edt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
    private fun await(block: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            var ready = false
            edt { ready = block() }
            if (ready) return
            Thread.sleep(10)
        }
        fail("Catalogue did not finish loading")
    }
    private fun executor(key: String, home: () -> List<CatalogItem>) = object : CatalogSourceExecutor {
        override val source = CatalogSource(key, key)
        override fun home() = home.invoke()
        override fun search(query: String) = emptyList<CatalogItem>()
        override fun detail(id: String) = CatalogDetail(CatalogItem(key, id, id))
        override fun play(flag: String, id: String) = CatalogPlayback()
    }

    @Test fun settingGroupsContinuousResultsWithoutRefetchAndSurvivesRestartAndBack() {
        val prefs = Preferences.userRoot().node("tvbox-desktop-test/${UUID.randomUUID()}")
        lateinit var panel: CatalogPanel
        val calls = AtomicInteger()
        val cards = (0 until 53).map { CatalogItem("one", "$it", "Video $it") }
        try {
            edt {
                panel = CatalogPanel(null, prefs)
                panel.addLocalExecutor(executor("one") { calls.incrementAndGet(); cards })
            }
            await { field<JList<*>>(panel, "results").model.size == 53 }
            edt {
                field<JButton>(panel, "thumbnailNext").doClick()
                val list = field<JList<CatalogItem>>(panel, "results")
                assertEquals("0", list.model.getElementAt(0).id)
                assertEquals(53, list.model.size)
                panel.focusSearch()
                panel.focusBrowse()
                assertEquals("0", list.model.getElementAt(0).id)
                field<JButton>(panel, "thumbnailNext").doClick()
                assertEquals(53, list.model.size)
                val spinner = field<JSpinner>(panel, "thumbnailsPerPage")
                // Exercise what a user types, rather than bypassing the editor via spinner.value.
                (spinner.editor as JSpinner.NumberEditor).textField.text = "12"
                assertEquals(53, list.model.size)
                assertEquals("0", list.model.getElementAt(0).id)
                assertEquals(12, DesktopCatalogSettings(prefs).thumbnailsPerPage)
                assertEquals("1–12 of 53 · Page 1 / 5", field<JLabel>(panel, "thumbnailPageLabel").text)
                (spinner.editor as JSpinner.NumberEditor).textField.text = "999"
                assertEquals(12, spinner.value)
                assertEquals(53, list.model.size)
                (spinner.editor as JSpinner.NumberEditor).textField.text = ""
                assertEquals(53, list.model.size)
                (spinner.editor as JSpinner.NumberEditor).textField.text = "6"
                assertEquals(53, list.model.size)
                // Arrow/model changes must still work after typing.
                spinner.value = spinner.nextValue
                assertEquals(53, list.model.size)
                (spinner.editor as JSpinner.NumberEditor).textField.text = "12"
                assertEquals(1, calls.get())
                panel.close()
                panel = CatalogPanel(null, prefs)
                assertEquals(12, field<JSpinner>(panel, "thumbnailsPerPage").value)
            }
        } finally {
            edt { panel.close() }
            prefs.removeNode()
        }
    }

    @Test fun changingCategorySizeFetchesEnoughVideosAndNextDoesNotSkipOverflow() {
        val prefs = Preferences.userRoot().node("tvbox-desktop-test/${UUID.randomUUID()}")
        val calls = java.util.concurrent.CopyOnWriteArrayList<Int>()
        lateinit var panel: CatalogPanel
        val provider = object : CatalogSourceExecutor {
            override val source = CatalogSource("bili", "Bili")
            override fun browseHome() = CatalogHome(types = listOf(CatalogType("anime", "Anime")))
            override fun category(typeId: String, page: Int, filters: Map<String, String>): CatalogPage {
                calls += page
                return CatalogPage((0 until 20).map {
                    val id = (page - 1) * 20 + it
                    CatalogItem("bili", "$id", "Video $id")
                }, page, 5, 100)
            }
            override fun search(query: String) = emptyList<CatalogItem>()
            override fun detail(id: String) = CatalogDetail()
            override fun play(flag: String, id: String) = CatalogPlayback()
        }
        try {
            edt { panel = CatalogPanel(null, prefs); panel.addLocalExecutor(provider) }
            await { field<JList<*>>(panel, "results").model.size == 24 }
            edt { (field<JSpinner>(panel, "thumbnailsPerPage").editor as JSpinner.NumberEditor).textField.text = "40" }
            await { field<JList<*>>(panel, "results").model.size == 40 }
            assertEquals(listOf(1, 2), calls.toList())
            edt { field<JButton>(panel, "thumbnailNext").doClick() }
            await { field<JList<CatalogItem>>(panel, "results").model.size == 80 }
            edt {
                val list = field<JList<CatalogItem>>(panel, "results")
                assertEquals("0", list.model.getElementAt(0).id)
                assertEquals("79", list.model.getElementAt(79).id)
                assertTrue(field<JLabel>(panel, "thumbnailPageLabel").text.startsWith("41–80"))
                (field<JSpinner>(panel, "thumbnailsPerPage").editor as JSpinner.NumberEditor).textField.text = "30"
            }
            await { field<JList<*>>(panel, "results").model.size == 30 }
            edt { field<JButton>(panel, "thumbnailNext").doClick() }
            await { field<JList<CatalogItem>>(panel, "results").model.size == 60 }
            assertEquals(listOf(1, 2, 3, 4), calls.toList())
        } finally { edt { panel.close() }; prefs.removeNode() }
    }

    @Test fun backgroundProvidersDoNotRestartOrInvalidateSelectedHome() {
        val prefs = Preferences.userRoot().node("tvbox-desktop-test/${UUID.randomUUID()}")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        lateinit var panel: CatalogPanel
        try {
            edt {
                panel = CatalogPanel(null, prefs)
                panel.addLocalExecutors(listOf(executor("first") {
                    calls.incrementAndGet()
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    listOf(CatalogItem("first", "video", "Video"))
                }), preserveBrowse = true)
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            edt { panel.addLocalExecutors(listOf(executor("second") { emptyList() }), preserveBrowse = true) }
            release.countDown()
            await { field<JList<*>>(panel, "results").model.size == 1 }
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
            edt { panel.close() }
            prefs.removeNode()
        }
    }
}
