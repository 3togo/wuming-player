package com.wuming.tv.desktop.ui

import com.wuming.tv.core.catalog.CatalogSource
import com.wuming.tv.core.catalog.CatalogType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogSourceChoicesTest {

    @Test
    fun `show all is synthetic and configured first provider remains present`() {
        val first = CatalogSource("first", "Original first provider")
        val second = CatalogSource("second", "Second provider")

        val choices = CatalogSourceChoices.from(listOf(first, second))

        assertEquals(listOf("Search All", first.name, second.name), choices.map { it.name })
        assertTrue(choices[0].isShowAll)
        assertEquals(null, choices[0].source)
        assertFalse(choices[1].isShowAll)
        assertEquals(first, choices[1].source)
        assertEquals(second, choices[2].source)
    }

    @Test
    fun `empty configuration does not expose an unusable show all choice`() {
        assertEquals(emptyList<CatalogSourceChoice>(), CatalogSourceChoices.from(emptyList()))
    }

    @Test
    fun `first configured provider owns Home when no choice was saved`() {
        val choices = CatalogSourceChoices.from(
            listOf(CatalogSource("first", "First"), CatalogSource("second", "Second")),
        )

        assertEquals(1, CatalogSourceChoices.initialSelectionIndex(choices, null, explicitlyShowAll = false))
        assertEquals("first", choices[1].source?.key)
    }

    @Test
    fun `saved provider or explicit show all overrides first configured provider`() {
        val choices = CatalogSourceChoices.from(
            listOf(CatalogSource("first", "First"), CatalogSource("second", "Second")),
        )

        assertEquals(2, CatalogSourceChoices.initialSelectionIndex(choices, "second", explicitlyShowAll = false))
        assertEquals(0, CatalogSourceChoices.initialSelectionIndex(choices, null, explicitlyShowAll = true))
    }

    @Test
    fun `show all media types retain their owning source`() {
        val source = CatalogSource("huya", "Huya")
        val type = CatalogType("game", "Games")

        val choice = CatalogTypeChoices.from(source, listOf(type), qualifySource = true).single()

        assertEquals("Huya · Games", choice.name)
        assertEquals("huya", choice.sourceKey)
        assertEquals(type, choice.type)
    }

    @Test
    fun `runtime index provider types lead show all without desktop qualification`() {
        val normal = CatalogTypeChoices.from(
            CatalogSource("normal", "Normal"),
            listOf(CatalogType("normal-type", "Normal type")),
            qualifySource = true,
        )
        val runtimeTypes = listOf(
            CatalogType("hot", "热门电影"),
            CatalogType("movie", "电影筛选"),
        )
        val index = CatalogTypeChoices.from(
            CatalogSource("home", "饭太硬", searchable = false, indexProvider = true),
            runtimeTypes,
            qualifySource = true,
        )

        val ordered = CatalogTypeChoices.homeFirst(normal + index)

        assertEquals(listOf("热门电影", "电影筛选", "Normal · Normal type"), ordered.map { it.name })
        assertEquals(runtimeTypes.map(CatalogType::id), ordered.take(2).map { it.type.id })
        assertTrue(ordered.take(2).all(CatalogTypeChoice::indexProvider))
    }

    @Test
    fun `index provider Home request is submitted before ordinary providers`() {
        val slowFirst = CatalogSource("slow", "Slow")
        val home = CatalogSource("home", "Home", searchable = false, indexProvider = true)
        val other = CatalogSource("other", "Other")

        assertEquals(
            listOf(home, slowFirst, other),
            CatalogHomeSourceOrder.prioritize(listOf(slowFirst, home, other)),
        )
    }
}
