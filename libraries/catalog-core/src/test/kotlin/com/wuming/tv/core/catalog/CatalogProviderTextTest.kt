package com.wuming.tv.core.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogProviderTextTest {
    @Test fun displaysTheCastAndDirectorNamesFromProviderLinks() {
        val names = listOf("张译", "张颂文", "李一桐", "张志坚", "吴刚", "倪大红", "韩童生", "李建义")
        val annotated = names.joinToString(" ", transform = ::link)
        assertEquals(names.joinToString(" "), CatalogProviderText.plain(annotated))
        assertEquals("徐纪周", CatalogProviderText.plain(link("徐纪周")))
    }

    @Test fun leavesUnannotatedAndIncompleteTextUntouched() {
        for (value in listOf("", "张译 / 张颂文", "A & B", "العربية", "[a=cr:{unfinished")) {
            assertEquals(value, CatalogProviderText.plain(value))
        }
    }

    @Test fun retainsSurroundingTextAndUsesTheVisibleLabel() {
        val value = "Guest: [a=cr:{\"id\":\"opaque/{pg}\",\"name\":\"metadata\"}/] Visible name [/a]."
        assertEquals("Guest: Visible name.", CatalogProviderText.plain(value))
    }

    private fun link(name: String) = """[a=cr:{"id":"$name/{pg}","name":"$name"}/]$name[/a]"""
}
