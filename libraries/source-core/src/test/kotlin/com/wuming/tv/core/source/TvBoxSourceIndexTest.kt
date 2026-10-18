package com.wuming.tv.core.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvBoxSourceIndexTest {

    private data class Site(val key: String, val name: String)

    @Test
    fun `indexByKey preserves insertion order and overwrites duplicates`() {
        val sites = listOf(Site("a", "1"), Site("b", "2"), Site("a", "3"))
        val index = TvBoxSourceIndex.indexByKey(sites) { it.key }
        assertEquals(listOf("a", "b"), index.keys.toList())
        assertEquals("3", index["a"]?.name)
    }

    @Test
    fun `distinctByKey keeps first occurrence`() {
        val sites = listOf(Site("a", "1"), Site("a", "2"), Site("b", "3"))
        val distinct = TvBoxSourceIndex.distinctByKey(sites) { it.key }
        assertEquals(listOf(Site("a", "1"), Site("b", "3")), distinct)
    }

    @Test
    fun `empty input yields empty index`() {
        val index = TvBoxSourceIndex.indexByName(emptyList<Site>()) { it.name }
        assertTrue(index.isEmpty())
        assertFalse(index.keys.iterator().hasNext())
    }
}
