package com.wuming.tv.desktop.jvmspider

import com.wuming.tv.core.catalog.*
import com.wuming.tv.core.runtime.guardspider.GuardedSpiderAbi
import com.wuming.tv.core.runtime.jvmspider.JvmSpiderSiteSpec
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class TrustedSourceRestorationTest {
    private val trust = GuardedSourceTrust("a".repeat(64), GuardedSpiderAbi.parse("armeabi-v7a"))
    private fun site(key: String) = JvmSpiderSiteSpec(key, key, "csp_Demo", artifactLocation = "https://example.test/shared.jar")
    private class Executor(key: String) : CatalogSourceExecutor, AutoCloseable {
        override val source = CatalogSource(key, key)
        var closed = false
        override fun home() = listOf(CatalogItem(source.key, "video", "Video", picture = "https://example.test/poster.jpg"))
        override fun search(query: String) = home()
        override fun detail(id: String) = CatalogDetail(home().single())
        override fun play(flag: String, id: String) = CatalogPlayback()
        override fun close() { closed = true }
    }

    @Test fun staleDigestDoesNotDiscardWorkingSourcesAndLaterGroupsStillRestore() {
        val first = Executor("first")
        val last = Executor("last")
        val stale = trust.copy(sha256 = "b".repeat(64))
        val other = site("last").copy(artifactLocation = "https://example.test/other.jar")
        val failures = mutableListOf<String>()
        val restored = restoreTrustedSources(
            listOf(site("first") to trust, site("stale") to stale, other to trust),
            activate = { specs, approval ->
                require(approval == trust) { "Spider artifact changed; inspect and approve its new SHA-256." }
                if (specs.single().key == "first") listOf(first) else listOf(last)
            },
            onFailure = { specs, _ -> failures += specs.single().key },
        )
        assertEquals(listOf(first, last), restored)
        assertEquals(listOf("stale"), failures)
        assertFalse(first.closed)
        assertFalse(last.closed)
        assertEquals("https://example.test/poster.jpg", ExecutorCatalogGateway(restored).home("first").single().picture)
    }

    @Test fun selectedProviderIsPublishedBeforeStartingTheRestOfItsBundle() {
        val events = mutableListOf<String>()
        val result = restoreTrustedSources(
            listOf(site("other") to trust, site("selected") to trust),
            activate = { specs, _ ->
                events += "start:${specs.joinToString { it.key }}"
                specs.map { Executor(it.key) }
            },
            onFailure = { _, _ -> fail("Unexpected failure") },
            preferredKey = "selected",
            onReady = { events += "ready:${it.joinToString { source -> source.source.key }}" },
        )
        assertEquals(listOf("start:selected", "ready:selected", "start:other", "ready:other"), events)
        result.forEach { (it as AutoCloseable).close() }
    }

    @Test fun cancellationDoesNotCloseHostsAlreadyTransferredToTheUi() {
        val selected = Executor("selected")
        try {
            restoreTrustedSources(
                listOf(site("other") to trust, site("selected") to trust),
                activate = { specs, _ -> if (specs.single().key == "selected") listOf(selected) else throw CancellationException() },
                onFailure = { _, _ -> fail("Cancellation must propagate") },
                preferredKey = "selected",
                onReady = {},
            )
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertFalse(selected.closed)
            selected.close()
        }
    }

    @Test fun allFailedGroupsAreReportedWithoutActivatingAnything() {
        var failures = 0
        val restored = restoreTrustedSources(listOf(site("stale") to trust),
            activate = { _, _ -> throw IllegalArgumentException("artifact changed") },
            onFailure = { _, _ -> failures++ })
        assertTrue(restored.isEmpty())
        assertEquals(1, failures)
    }

    @Test fun cancellationStillClosesPreviouslyRestoredHosts() {
        val first = Executor("first")
        try {
            restoreTrustedSources(listOf(site("first") to trust, site("cancel").copy(artifactLocation = "other") to trust),
                activate = { specs, _ -> if (specs.single().key == "first") listOf(first) else throw CancellationException() },
                onFailure = { _, _ -> fail("Cancellation must propagate") })
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertTrue(first.closed)
        }
    }
}
