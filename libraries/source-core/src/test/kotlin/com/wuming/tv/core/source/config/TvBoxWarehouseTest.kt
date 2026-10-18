package com.wuming.tv.core.source.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvBoxWarehouseTest {
    @Test fun resolvesChildrenAndKeepsTheirOrder() {
        val payload = """{"urls":[
            {"name":"Primary","url":"./first.json"},
            {"name":"Backup","url":"../backup.json"},
            {"name":"Duplicate","url":"./first.json"},
            {"name":"Bad","url":"javascript:alert(1)"},
            {"name":"中文","url":"https://饭太硬.cc/电视.json"},
            {"url":"https://example.org/third.json"}
        ]}""".toByteArray()
        val children = TvBoxWarehouse.parseOrNull(payload, "https://example.org/config/index.json")!!
        assertEquals(listOf("Primary", "Backup", "中文", "https://example.org/third.json"), children.map { it.name })
        assertEquals(listOf("https://example.org/config/first.json", "https://example.org/backup.json",
            "https://饭太硬.cc/电视.json", "https://example.org/third.json"), children.map { it.url })
    }

    @Test fun ordinaryConfigIsNotAWarehouse() {
        assertNull(TvBoxWarehouse.parseOrNull("""{"sites":[]}""".toByteArray(), "https://example.org/x.json"))
        assertTrue(TvBoxWarehouse.isLikelyIndex("影视多仓", false))
        assertTrue(TvBoxWarehouse.isLikelyIndex("Marked", true))
        assertFalse(TvBoxWarehouse.isLikelyIndex("普通线路", false))
    }
}
