package com.wuming.tv.core.catalog

import org.junit.Assert.*
import org.junit.Test

class CatalogSourceRosterTest {
    @Test fun configurationControlsOrderAndNamesRegardlessOfActivationOrder() {
        val configured = listOf(CatalogSource("first", "Configured first"),
            CatalogSource("pending", "Not yet active"), CatalogSource("last", "Configured last", false))
        val active = listOf(CatalogSource("last", "Runtime name"), CatalogSource("first", "Other name"),
            CatalogSource("removed", "Stale runtime"))
        assertEquals(configured, CatalogSourceRoster.displayed(configured, active))
        assertEquals(listOf(configured[0], configured[2]), CatalogSourceRoster.runnable(configured, active))
        assertTrue(CatalogSourceRoster.targets(CatalogSourceRoster.runnable(configured, active), "pending").isEmpty())
        assertEquals(listOf(configured[0], configured[2]),
            CatalogSourceRoster.targets(CatalogSourceRoster.runnable(configured, active), null))
    }

    @Test fun standaloneExecutorsRemainUsableWithoutAConfiguration() {
        val active = listOf(CatalogSource("one", "One"))
        assertEquals(active, CatalogSourceRoster.displayed(emptyList(), active))
        assertEquals(active, CatalogSourceRoster.runnable(emptyList(), active))
    }
}
