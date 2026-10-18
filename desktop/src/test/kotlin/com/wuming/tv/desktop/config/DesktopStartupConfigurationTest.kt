package com.wuming.tv.desktop.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class DesktopStartupConfigurationTest {
    private val depot = DesktopDepot(listOf(
        DesktopDepotSource("Other", "https://other.test/tv"),
        DesktopDepotSource("饭太硬", "https://default.test/tv"),
    ), emptyList())

    @Test fun automaticApprovalIsLimitedToTheExactBuiltInDefault() {
        assertTrue(DesktopStartupConfiguration.automaticallyApprove(depot, "https://default.test/tv"))
        listOf("https://other.test/tv", "https://custom.test/tv", "https://default.test/tv/other",
            "https://default.test/tv?url=custom", "https://default.test.evil/tv").forEach {
            assertFalse(DesktopStartupConfiguration.automaticallyApprove(depot, it))
        }
    }

    @Test fun firstStartUsesBundledDefaultRatherThanAnUnrelatedFirstEntry() {
        assertEquals("https://default.test/tv", DesktopStartupConfiguration.location(depot, "", null))
    }

    @Test fun savedCustomUrlTakesPrecedenceOverRememberedDepot() {
        assertEquals("https://custom.test/tv", DesktopStartupConfiguration.location(depot,
            " https://custom.test/tv ", "https://other.test/tv"))
    }

    @Test fun rememberedDepotIsUsedWhenThereIsNoWorkingUrl() {
        assertEquals("https://other.test/tv", DesktopStartupConfiguration.location(depot, "", "https://other.test/tv"))
        assertEquals("https://default.test/tv", DesktopStartupConfiguration.location(depot, "", "https://removed.test/tv"))
    }

    @Test fun absentPreferredSourceWaitsForExplicitSelection() {
        val pruned = DesktopDepot(listOf(DesktopDepotSource("Other", "https://other.test/tv")), emptyList())
        assertEquals("", DesktopStartupConfiguration.location(pruned, "", null))
        assertFalse(DesktopStartupConfiguration.automaticallyApprove(pruned, "https://other.test/tv"))
        val empty = DesktopDepot(emptyList(), emptyList())
        assertEquals("", DesktopStartupConfiguration.location(empty, "", null))
        assertFalse(DesktopStartupConfiguration.automaticallyApprove(empty, ""))
    }
}
