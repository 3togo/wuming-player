package com.wuming.tv.core.search

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class TitleAliasRepositoryTest {
    private class Memory(var json: String) : TitleAliasRepository.Store {
        override fun read() = json
        override fun write(value: String) { json = value }
    }

    @Test fun learnedAliasesRetainTheirPersistedSchemaAcrossRestarts() {
        val store = Memory("""
            [{"canonicalId":"fixture:1","aliases":["Saved movie","已存电影"],
              "year":"2026","mediaType":"movie"}]
        """.trimIndent())
        fun repository() = TitleAliasRepository(false, TitleNormalizer.chinese(), store,
            TitleAliasRepository.HttpPort { _, _ -> error("Cached aliases must not use HTTP") })
        val loaded = repository()
        assertEquals("fixture:1", loaded.resolve("Saved movie").canonicalId())
        assertEquals("id:fixture:1", loaded.canonicalKey("已存电影"))
        loaded.learn(TitleIdentity("fixture:2", listOf("Learned movie", "新电影"), "2025", "movie"))
        val saved = JsonParser.parseString(store.json).asJsonArray
            .first { it.asJsonObject["canonicalId"].asString == "fixture:2" }.asJsonObject
        assertEquals(setOf("canonicalId", "aliases", "year", "mediaType"), saved.keySet())
        val restarted = repository()
        assertEquals("fixture:1", restarted.resolve("Saved movie").canonicalId())
        assertEquals("fixture:2", restarted.resolve("新电影").canonicalId())
    }
}
