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

    @Test fun acceptsZyPlayerStoresAlongsideStandardChildren() {
        val payload = """{"urls":[{"name":"Standard","url":"./one.json"}],"storeHouse":[
            {"sourceName":"Duplicate","sourceUrl":"./one.json"},
            {"sourceName":"ZyPlayer","sourceUrl":"../two.json"},
            {"sourceName":"Invalid","sourceUrl":"javascript:alert(1)"}
        ]}""".toByteArray()
        val children = TvBoxWarehouse.parseOrNull(payload, "https://example.org/config/index.json")!!
        assertEquals(listOf("Standard", "ZyPlayer"), children.map { it.name })
        assertEquals("https://example.org/two.json", children.last().url)
    }

    @Test fun unwrapsExportSectionsBeforeResolvingResources() {
        val input = com.google.gson.JsonParser.parseString("""{
            "sites":{"data":[{"api":"./source.js"}]},
            "iptv":{"data":[{"url":"./live.m3u"}]},
            "lives":[],
            "analyzes":{"data":[{"name":"Parser"}]}
        }""").asJsonObject
        val normalized = ConfigResourceResolver.rewrite(input) {
            ConfigResourceResolver.resolve("https://example.org/config/index.json", it)
        }
        assertEquals("https://example.org/config/source.js", normalized.getAsJsonArray("sites")[0].asJsonObject.get("api").asString)
        assertEquals(0, normalized.getAsJsonArray("lives").size())
        assertEquals("Parser", normalized.getAsJsonArray("parses")[0].asJsonObject.get("name").asString)
        assertTrue(input.get("sites").isJsonObject)
    }

    @Test fun ordinaryConfigIsNotAWarehouse() {
        assertNull(TvBoxWarehouse.parseOrNull("""{"sites":[]}""".toByteArray(), "https://example.org/x.json"))
        assertTrue(TvBoxWarehouse.isLikelyIndex("影视多仓", false))
        assertTrue(TvBoxWarehouse.isLikelyIndex("Marked", true))
        assertFalse(TvBoxWarehouse.isLikelyIndex("普通线路", false))
    }
}
