package com.wuming.tv.core.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CatalogSourceAuditTest {

    private val source = CatalogSource("demo", "Demo")

    @Test
    fun auditsDiscoverySearchDetailAndPlaybackThroughOneGateway() {
        val report = CatalogSourceAuditor().audit(FakeGateway(), source, "Movie")

        assertEquals(CatalogAuditStatus.PASSED, report.status)
        assertEquals(CatalogAuditStage.entries, report.steps.map { it.stage })
        assertEquals(List(4) { CatalogAuditStatus.PASSED }, report.steps.map { it.status })
        assertFalse(report.steps.any { "opaque" in it.message })
    }

    @Test
    fun distinguishesPlatformUnsupportedPlaybackFromProviderFailure() {
        val gateway = FakeGateway(playback = CatalogPlayback(false, message = "Requires Android DRM."))

        val report = CatalogSourceAuditor().audit(gateway, source, "Movie")

        assertEquals(CatalogAuditStatus.UNSUPPORTED, report.status)
        assertEquals(CatalogAuditStatus.UNSUPPORTED, report.steps.last().status)
    }

    @Test
    fun skipsDependentStagesWhenSearchReturnsNothing() {
        val report = CatalogSourceAuditor().audit(FakeGateway(items = emptyList()), source, "Missing")

        assertEquals(CatalogAuditStatus.FAILED, report.status)
        assertEquals(
            listOf(
                CatalogAuditStatus.PASSED,
                CatalogAuditStatus.FAILED,
                CatalogAuditStatus.SKIPPED,
                CatalogAuditStatus.SKIPPED,
            ),
            report.steps.map { it.status },
        )
    }

    @Test
    fun cancellationStopsBeforeCallingTheNextGatewayStage() {
        val gateway = FakeGateway()
        var cancel = false
        val report = CatalogSourceAuditor().audit(
            gateway,
            source,
            "Movie",
            CatalogAuditCancellation { cancel },
        ) { step -> if (step.stage == CatalogAuditStage.SOURCES) cancel = true }

        assertEquals(CatalogAuditStatus.CANCELLED, report.status)
        assertEquals(0, gateway.searchCalls)
        assertEquals(CatalogAuditStatus.CANCELLED, report.steps[1].status)
    }

    private class FakeGateway(
        private val items: List<CatalogItem> = listOf(CatalogItem("demo", "opaque-item", "Movie")),
        private val playback: CatalogPlayback = CatalogPlayback(
            true,
            "https://media.example/video.m3u8",
        ),
    ) : CatalogGateway {
        var searchCalls = 0

        override fun sources() = listOf(CatalogSource("demo", "Demo"))

        override fun search(source: String, query: String): List<CatalogItem> {
            searchCalls++
            return items
        }

        override fun detail(source: String, id: String) = CatalogDetail(
            item = CatalogItem(source, id, "Movie"),
            lines = listOf(CatalogLine("main", "Main", listOf(CatalogEpisode("1", "opaque-episode")))),
        )

        override fun play(source: String, flag: String, id: String) = playback
    }
}
