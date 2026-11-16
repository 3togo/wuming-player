package com.wuming.tv.core.base

import org.junit.Assert.*
import org.junit.Test

class ListPagesTest {
    @Test fun pagesRetainOrderAndRespectTheExactRequestedSize() {
        val items = listOf("a", "b", "c", "d", "e")
        assertEquals(listOf("a"), ListPages.slice(items, 0, 1))
        assertEquals(listOf("b"), ListPages.slice(items, 1, 1))
        assertEquals(listOf("c", "d"), ListPages.slice(items, 1, 2))
        assertEquals(listOf("e"), ListPages.slice(items, 2, 2))
        assertEquals(items, ListPages.slice(items, 0, Int.MAX_VALUE))
    }

    @Test fun emptyAndOutOfRangePagesNeverWrapOrRepeatTheLastPage() {
        assertTrue(ListPages.slice(emptyList<String>(), 0, 1).isEmpty())
        assertTrue(ListPages.slice(listOf("a", "b"), 1, 2).isEmpty())
        assertTrue(ListPages.slice(listOf("a", "b"), Int.MAX_VALUE, 2).isEmpty())
        assertTrue(ListPages.slice(listOf("a", "b"), Int.MAX_VALUE, Int.MAX_VALUE).isEmpty())
    }

    @Test fun lastPageNearMaximumListSizeDoesNotOverflowItsEnd() {
        val items = object : AbstractList<Int>() {
            override val size = Int.MAX_VALUE
            override fun get(index: Int): Int { require(index in 0 until size); return index }
        }
        assertEquals((2_147_483_640..2_147_483_646).toList(), ListPages.slice(items, 17_895_697, 120))
        assertTrue(ListPages.slice(items, 17_895_698, 120).isEmpty())
        assertEquals(Int.MAX_VALUE, ListPages.slice(items, 0, Int.MAX_VALUE).size)
        assertTrue(ListPages.slice(items, 1, Int.MAX_VALUE).isEmpty())
    }

    @Test fun invalidArgumentsAreRejected() {
        for ((page, size) in listOf(-1 to 1, 0 to 0, 0 to -1)) {
            assertThrows(IllegalArgumentException::class.java) { ListPages.slice(listOf("a"), page, size) }
        }
    }
}
