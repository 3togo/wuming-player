package com.wuming.android.tv.bean

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgDataTest {

    @Test
    fun `getTitle returns empty for null title`() {
        val data = EpgData()
        assertEquals("", data.getTitle())
    }

    @Test
    fun `getTitle returns value when set`() {
        val data = EpgData()
        data.setTitle("News")
        assertEquals("News", data.getTitle())
    }

    @Test
    fun `getStart returns empty for null start`() {
        val data = EpgData()
        assertEquals("", data.getStart())
    }

    @Test
    fun `getEnd returns empty for null end`() {
        val data = EpgData()
        assertEquals("", data.getEnd())
    }

    @Test
    fun `isSelected defaults to false`() {
        val data = EpgData()
        assertFalse(data.isSelected())
    }

    @Test
    fun `setSelected sets value`() {
        val data = EpgData()
        data.setSelected(true)
        assertTrue(data.isSelected())
    }

    @Test
    fun `setSelected with EpgData sets true when equal`() {
        val data1 = EpgData()
        data1.setTitle("News")
        data1.setStart("10:00")
        data1.setEnd("11:00")
        val data2 = EpgData()
        data2.setTitle("News")
        data2.setStart("10:00")
        data2.setEnd("11:00")
        data1.setSelected(data2)
        assertTrue(data1.isSelected())
    }

    @Test
    fun `setSelected with EpgData sets false when not equal`() {
        val data1 = EpgData()
        data1.setTitle("News")
        val data2 = EpgData()
        data2.setTitle("Sports")
        data1.setSelected(data2)
        assertFalse(data1.isSelected())
    }

    @Test
    fun `startTime defaults to zero`() {
        val data = EpgData()
        assertEquals(0L, data.startTime)
    }

    @Test
    fun `endTime defaults to zero`() {
        val data = EpgData()
        assertEquals(0L, data.endTime)
    }

    @Test
    fun `startTime can be set`() {
        val data = EpgData()
        data.startTime = 1000L
        assertEquals(1000L, data.startTime)
    }

    @Test
    fun `endTime can be set`() {
        val data = EpgData()
        data.endTime = 2000L
        assertEquals(2000L, data.endTime)
    }

    @Test
    fun `format returns empty when title is empty`() {
        val data = EpgData()
        assertEquals("", data.format())
    }

    @Test
    fun `format returns title only when start and end are empty`() {
        val data = EpgData()
        data.setTitle("News")
        assertEquals("News", data.format())
    }

    @Test
    fun `format returns full format with start end and title`() {
        val data = EpgData()
        data.setTitle("News")
        data.setStart("10:00")
        data.setEnd("11:00")
        assertEquals("10:00 ~ 11:00  News", data.format())
    }

    @Test
    fun `getTime returns empty when start and end are empty`() {
        val data = EpgData()
        assertEquals("", data.getTime())
    }

    @Test
    fun `getTime returns start and end`() {
        val data = EpgData()
        data.setStart("10:00")
        data.setEnd("11:00")
        assertEquals("10:00 ~ 11:00", data.getTime())
    }

    @Test
    fun `equals returns true for same title start end`() {
        val data1 = EpgData()
        data1.setTitle("News")
        data1.setStart("10:00")
        data1.setEnd("11:00")
        val data2 = EpgData()
        data2.setTitle("News")
        data2.setStart("10:00")
        data2.setEnd("11:00")
        assertEquals(data1, data2)
    }

    @Test
    fun `equals returns false for different title`() {
        val data1 = EpgData()
        data1.setTitle("News")
        val data2 = EpgData()
        data2.setTitle("Sports")
        assertNotEquals(data1, data2)
    }

    @Test
    fun `equals returns true for self`() {
        val data = EpgData()
        assertEquals(data, data)
    }

    @Test
    fun `hashCode is consistent with equals`() {
        val data1 = EpgData()
        data1.setTitle("News")
        data1.setStart("10:00")
        data1.setEnd("11:00")
        val data2 = EpgData()
        data2.setTitle("News")
        data2.setStart("10:00")
        data2.setEnd("11:00")
        assertEquals(data1.hashCode(), data2.hashCode())
    }
}
