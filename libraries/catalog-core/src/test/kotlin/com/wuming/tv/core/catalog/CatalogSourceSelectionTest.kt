package com.wuming.tv.core.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogSourceSelectionTest {

    @Test
    fun `synthetic first choice selects every real source`() {
        val sources = listOf(
            CatalogSource("first", "A provider name, not an All label"),
            CatalogSource("second", "Another provider"),
        )

        assertEquals(sources, CatalogSourceSelection.targets(sources, 0))
    }

    @Test
    fun `provider choices are offset by synthetic first choice`() {
        val sources = listOf(
            CatalogSource("first", "First"),
            CatalogSource("second", "Second"),
        )

        assertEquals(listOf(sources[0]), CatalogSourceSelection.targets(sources, 1))
        assertEquals(listOf(sources[1]), CatalogSourceSelection.targets(sources, 2))
        assertEquals(emptyList<CatalogSource>(), CatalogSourceSelection.targets(sources, -1))
        assertEquals(emptyList<CatalogSource>(), CatalogSourceSelection.targets(sources, 3))
    }

    @Test
    fun `show all with no real sources has no targets`() {
        assertEquals(emptyList<CatalogSource>(), CatalogSourceSelection.targets(emptyList(), 0))
    }
}
