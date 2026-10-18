package com.wuming.tv.core.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class CatVodCatalogCodecTest {

    private val codec = CatVodCatalogCodec(CatalogSource("source", "Source"))

    @Test
    fun `detail retains requested id when provider omits vod id`() {
        val detail = codec.detail(
            """{"list":[{"vod_name":"Title","vod_play_from":"direct","vod_play_url":"Episode${'$'}https://media.example/video.m3u8"}]}""",
            "requested-id",
        )

        assertEquals("requested-id", detail.item.id)
        assertEquals("Title", detail.item.name)
        assertEquals(1, detail.lines.single().episodes.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `detail without provider or requested id remains invalid`() {
        codec.detail("""{"list":[{"vod_name":"Title"}]}""")
    }

    @Test
    fun `playback accepts android object form multi url`() {
        val playback = codec.playback(
            """{"parse":0,"url":{"values":[{"n":"low","v":"https://media.example/low.mpd"},{"n":"high","v":"https://media.example/high.mpd"}],"position":1},"format":"application/dash+xml"}""",
        )

        assertEquals(true, playback.supported)
        assertEquals("https://media.example/high.mpd", playback.url)
        assertEquals("application/dash+xml", playback.format)
    }

    @Test
    fun `playback accepts CatVod alternating quality and url array`() {
        val playback = codec.playback(
            """{"parse":0,"url":["HD","https://media.example/high.m3u8","SD","https://media.example/low.m3u8"]}""",
        )

        assertEquals(true, playback.supported)
        assertEquals("https://media.example/high.m3u8", playback.url)
    }

    @Test
    fun `home extracts leanback types and host scoped filters`() {
        val home = codec.home(
            """{
              "class":[{"type_id":"movie","type_name":"Movies","type_flag":"0"}],
              "filters":{"movie":[{"key":"year","name":"Year","init":"2026","value":[{"n":"All","v":""},{"n":"2026","v":"2026"}]}]},
              "list":[{"vod_id":"featured","vod_name":"Featured"}]
            }""",
        )

        assertEquals("Featured", home.items.single().name)
        assertEquals("movie", home.types.single().id)
        assertEquals("year", home.types.single().filters.single().key)
        assertEquals(listOf("", "2026"), home.types.single().filters.single().values.map(CatalogFilterValue::value))
        assertEquals(mapOf("year" to "2026"), CatalogFilterSelectionPolicy.defaults(home.types.single()))
    }

    @Test
    fun `list items retain recommendation metadata`() {
        val item = codec.search(
            """{"list":[{"vod_id":"movie","vod_name":"Movie","type_name":"Action","vod_area":"Taiwan","vod_director":"Director","vod_actor":"Actor","vod_content":"Adventure"}]}""",
        ).single()

        assertEquals("Action", item.typeName)
        assertEquals("Taiwan", item.area)
        assertEquals("Director", item.director)
        assertEquals("Actor", item.actor)
        assertEquals("Adventure", item.content)
    }

    @Test
    fun `explicit empty default remains different from a missing default`() {
        val home = codec.home(
            """{"class":[{"type_id":"movie"},{"type_id":"series"}],"filters":{"movie":{"key":"area","init":"","value":[{"n":"All","v":""}]},"series":{"key":"area","value":[{"n":"All","v":""}]}}}""",
        )

        assertEquals(mapOf("area" to ""), CatalogFilterSelectionPolicy.defaults(home.types[0]))
        assertEquals(emptyMap<String, String>(), CatalogFilterSelectionPolicy.defaults(home.types[1]))
    }

    @Test
    fun `home accepts android single object filter shape`() {
        val home = codec.home(
            """{"class":[{"type_id":"series","type_name":"Series"}],"filters":{"series":{"key":"area","name":"Area","value":[{"n":"China","v":"cn"}]}}}""",
        )

        assertEquals("area", home.types.single().filters.single().key)
        assertEquals("cn", home.types.single().filters.single().values.single().value)
    }

    @Test
    fun `category retains pagination and distinguishes folders from media`() {
        val page = codec.category(
            """{"page":2,"pagecount":4,"total":40,"list":[
              {"vod_id":"folder","vod_name":"Season","vod_tag":"folder"},
              {"vod_id":"movie","vod_name":"Movie"}
            ]}""",
            2,
        )

        assertEquals(2, page.page)
        assertEquals(true, page.hasPrevious)
        assertEquals(true, page.hasNext)
        assertEquals(CatalogItemKind.FOLDER, page.items[0].kind)
        assertEquals(CatalogItemKind.MEDIA, page.items[1].kind)
    }
}
