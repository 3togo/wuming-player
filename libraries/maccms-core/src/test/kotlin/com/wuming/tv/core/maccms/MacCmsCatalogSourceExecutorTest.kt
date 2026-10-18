package com.wuming.tv.core.maccms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class MacCmsCatalogSourceExecutorTest {

    private val json = """
        {
          "code": 1,
          "page": "2",
          "pagecount": "3",
          "total": "60",
          "class": [{"type_id": "1", "type_name": "电影"}, {"type_id": "2", "type_name": "剧集"}],
          "list": [
            {"vod_id": "11", "vod_name": "示例影片", "vod_pic": "https://img.test/1.jpg",
             "vod_remarks": "HD", "vod_year": "2026"}
          ]
        }
    """.trimIndent()

    private val jsonDetail = """
        {
          "list": [{
            "vod_id": "11", "vod_name": "示例影片", "vod_pic": "https://img.test/1.jpg",
            "vod_content": "简介", "vod_actor": "演员", "vod_director": "导演", "vod_area": "大陆",
            "vod_play_from": "m3u8", "vod_play_url": "第1集${'$'}https://media.test/1.m3u8#第2集${'$'}https://media.test/2.m3u8"
          }]
        }
    """.trimIndent()

    private val xml = """
        <?xml version="1.0" encoding="utf-8"?>
        <rss version="5.1">
          <list page="2" pagecount="7" pagesize="20" recordcount="140">
            <video>
              <id>21</id><tid>1</tid><name>XML 影片</name><type>电影</type>
              <pic>https://img.test/2.jpg</pic><note>更新至 3 集</note><year>2025</year>
              <area>大陆</area><actor>演员</actor><director>导演</director>
              <dl><dd flag="m3u8">第1集${'$'}https://media.test/x1.m3u8#第2集${'$'}https://media.test/x2.m3u8</dd></dl>
              <des><![CDATA[XML 简介]]></des>
            </video>
          </list>
          <class><ty id="1">电影</ty><ty id="2">剧集</ty></class>
        </rss>
    """.trimIndent()

    @Test
    fun readsJsonCollectionHomeCategoryDetailAndPlayback() {
        val requests = mutableListOf<Map<String, String>>()
        val executor = MacCmsCatalogSourceExecutor(
            MacCmsSiteSpec("demo", "Demo", "https://api.test/provide/vod/", type = 1, ext = "token"),
        ) { _, _, params -> requests += params; if (params["ids"] != null) jsonDetail else json }

        val home = executor.browseHome()
        assertEquals(listOf("电影", "剧集"), home.types.map { it.name })
        assertEquals("11", home.items.single().id)
        assertEquals("https://img.test/1.jpg", home.items.single().picture)

        val page = executor.category("1", 2, mapOf("class" to "动作"))
        assertEquals(1, page.items.size)
        assertEquals(2, page.page)
        assertEquals(3, page.pageCount)
        assertEquals(60, page.total)
        assertEquals(mapOf("ac" to "detail", "extend" to "token", "t" to "1", "pg" to "2", "f" to "{\"class\":\"动作\"}"),
            requests.last())

        val detail = executor.detail("11")
        assertEquals("简介", detail.content)
        assertEquals("演员", detail.actor)
        assertEquals(2, detail.lines.single().episodes.size)
        assertEquals("https://media.test/2.m3u8", detail.lines.single().episodes[1].id)

        assertTrue(executor.play("m3u8", "https://media.test/1.m3u8").supported)
        assertEquals("MacCMS source returned an unusable playback URL.", executor.play("m3u8", "relative/path").message)
    }

    @Test
    fun readsXmlCollectionHomeCategoryAndDetail() {
        val executor = MacCmsCatalogSourceExecutor(
            MacCmsSiteSpec("demo", "Demo", "https://api.test/xml.php", type = 0),
        ) { _, _, _ -> xml }

        val home = executor.browseHome()
        assertEquals(listOf("1", "2"), home.types.map { it.id })
        assertEquals("21", home.items.single().id)
        assertEquals("更新至 3 集", home.items.single().remarks)

        val page = executor.category("1", 2)
        assertEquals(2, page.page)
        assertEquals(7, page.pageCount)
        assertEquals(140, page.total)

        val detail = executor.detail("21")
        assertEquals("XML 简介", detail.content)
        assertEquals("m3u8", detail.lines.single().flag)
        assertEquals("https://media.test/x1.m3u8", detail.lines.single().episodes.first().id)
    }

    @Test
    fun retriesJsonListingWithTheVideolistActionWhenDetailReturnsNothing() {
        val requested = mutableListOf<String>()
        val executor = MacCmsCatalogSourceExecutor(
            MacCmsSiteSpec("demo", "Demo", "https://api.test/provide/vod/", type = 1),
        ) { _, _, params ->
            requested += params["ac"].orEmpty()
            if (params["ac"] == "videolist") json else "{\"list\":[]}"
        }

        assertEquals(1, executor.category("1", 1).items.size)
        assertEquals(listOf("detail", "videolist"), requested)
    }

    @Test
    fun redactsSiteExtensionInTextOutput() {
        val spec = MacCmsSiteSpec("demo", "Demo", "https://api.test/", type = 1, ext = "secret-token")
        assertTrue(spec.toString().contains("<redacted>"))
        assertTrue(!spec.toString().contains("secret-token"))
    }

    @Test
    fun searchAndDetailRetryLegacyActionPreservingParameters() {
        val requests = mutableListOf<Map<String, String>>()
        val executor = MacCmsCatalogSourceExecutor(
            MacCmsSiteSpec("demo", "Demo", "https://api.test/", type = 1, ext = "token"),
        ) { _, _, params ->
            requests += params.toMap()
            if (params["ac"] == "detail") "{\"list\":[]}"
            else if (params.containsKey("ids")) jsonDetail else json
        }
        assertEquals("11", executor.search("hello world").single().id)
        assertEquals(listOf("detail", "videolist"), requests.map { it["ac"] })
        assertEquals(requests[0] + ("ac" to "videolist"), requests[1])
        assertEquals("hello world", requests[1]["wd"])
        assertEquals("token", requests[1]["extend"])
        requests.clear()
        assertEquals(2, executor.detail("11").lines.single().episodes.size)
        assertEquals(requests[0] + ("ac" to "videolist"), requests[1])
        assertEquals("11", requests[1]["ids"])
    }

    @Test
    fun emptyFallbackStopsAfterTwoRequests() {
        var calls = 0
        val executor = MacCmsCatalogSourceExecutor(
            MacCmsSiteSpec("demo", "Demo", "https://api.test/", type = 1),
        ) { _, _, _ -> calls++; "{\"list\":[]}" }
        assertTrue(executor.search("none").isEmpty())
        assertEquals(2, calls)
        calls = 0
        assertThrows(IllegalArgumentException::class.java) { executor.detail("11") }
        assertEquals(2, calls)
    }

    @Test
    fun successfulDetailWithoutIdDoesNotTriggerFallback() {
        var calls = 0
        val executor = MacCmsCatalogSourceExecutor(
            MacCmsSiteSpec("demo", "Demo", "https://api.test/", type = 1),
        ) { _, _, _ -> calls++; jsonDetail.replace("\"vod_id\": \"11\",", "") }
        assertEquals("11", executor.detail("11").item.id)
        assertEquals(1, calls)
    }

    @Test
    fun xmlSearchAndDetailNeverRetryJsonAction() {
        val requests = mutableListOf<String>()
        val executor = MacCmsCatalogSourceExecutor(
            MacCmsSiteSpec("demo", "Demo", "https://api.test/", type = 0),
        ) { _, _, params -> requests += params.getValue("ac"); "<rss><list/></rss>" }
        assertTrue(executor.search("none").isEmpty())
        assertThrows(IllegalArgumentException::class.java) { executor.detail("11") }
        assertEquals(listOf("videolist", "videolist"), requests)
    }

    @Test
    fun transportAndMalformedPayloadErrorsAreNotRetried() {
        for (transportFailure in listOf(true, false)) {
            var calls = 0
            val executor = MacCmsCatalogSourceExecutor(
                MacCmsSiteSpec("demo", "Demo", "https://api.test/", type = 1),
            ) { _, _, _ ->
                calls++
                if (transportFailure) throw IllegalStateException("offline")
                "{"
            }
            assertThrows(Exception::class.java) { executor.search("query") }
            assertEquals(1, calls)
            calls = 0
            assertThrows(Exception::class.java) { executor.detail("11") }
            assertEquals(1, calls)
        }
    }

    @Test
    fun xmlHomeReturnsItemsAndTypes() {
        val executor = MacCmsCatalogSourceExecutor(
            MacCmsSiteSpec("demo", "Demo", "https://api.test/xml.php", type = 0),
        ) { _, _, _ -> xml }

        val home = executor.browseHome()
        assertEquals(2, home.types.size)
        assertEquals("1", home.types[0].id)
        assertEquals("电影", home.types[0].name)
        assertEquals("2", home.types[1].id)
        assertEquals("剧集", home.types[1].name)
        assertEquals(1, home.items.size)
        assertEquals("21", home.items[0].id)
        assertEquals("XML 影片", home.items[0].name)
    }

    @Test
    fun xmlCategoryReturnsPageMetadata() {
        val executor = MacCmsCatalogSourceExecutor(
            MacCmsSiteSpec("demo", "Demo", "https://api.test/xml.php", type = 0),
        ) { _, _, _ -> xml }

        val page = executor.category("1", 2)
        assertEquals(2, page.page)
        assertEquals(7, page.pageCount)
        assertEquals(140, page.total)
        assertEquals(1, page.items.size)
    }

    @Test
    fun xmlDetailReturnsContentAndLines() {
        val executor = MacCmsCatalogSourceExecutor(
            MacCmsSiteSpec("demo", "Demo", "https://api.test/xml.php", type = 0),
        ) { _, _, _ -> xml }

        val detail = executor.detail("21")
        assertEquals("XML 简介", detail.content)
        assertEquals("演员", detail.actor)
        assertEquals("导演", detail.director)
        assertEquals("大陆", detail.area)
        assertEquals(1, detail.lines.size)
        assertEquals("m3u8", detail.lines[0].flag)
        assertEquals(2, detail.lines[0].episodes.size)
        assertEquals("第1集", detail.lines[0].episodes[0].name)
        assertEquals("https://media.test/x1.m3u8", detail.lines[0].episodes[0].id)
    }

    @Test
    fun xmlSearchReturnsItems() {
        val executor = MacCmsCatalogSourceExecutor(
            MacCmsSiteSpec("demo", "Demo", "https://api.test/xml.php", type = 0),
        ) { _, _, _ -> xml }

        val results = executor.search("影片")
        assertEquals(1, results.size)
        assertEquals("21", results[0].id)
    }

    @Test
    fun xmlDetailWithMissingVideoThrows() {
        val executor = MacCmsCatalogSourceExecutor(
            MacCmsSiteSpec("demo", "Demo", "https://api.test/xml.php", type = 0),
        ) { _, _, _ -> "<rss><list/></rss>" }

        assertThrows(IllegalArgumentException::class.java) { executor.detail("99") }
    }

    @Test
    fun xmlHomeWithNoClassReturnsEmptyTypes() {
        val executor = MacCmsCatalogSourceExecutor(
            MacCmsSiteSpec("demo", "Demo", "https://api.test/xml.php", type = 0),
        ) { _, _, _ -> "<rss><list><video><id>1</id><name>CH</name></video></list></rss>" }

        val home = executor.browseHome()
        assertTrue(home.types.isEmpty())
        assertEquals(1, home.items.size)
    }

    @Test
    fun jsonHomeReturnsItemsAndTypes() {
        val executor = MacCmsCatalogSourceExecutor(
            MacCmsSiteSpec("demo", "Demo", "https://api.test/provide/vod/", type = 1),
        ) { _, _, params -> if (params["ids"] != null) jsonDetail else json }

        val home = executor.browseHome()
        assertEquals(2, home.types.size)
        assertEquals("电影", home.types[0].name)
        assertEquals("剧集", home.types[1].name)
        assertEquals(1, home.items.size)
        assertEquals("11", home.items[0].id)
    }

    @Test
    fun jsonDetailReturnsContentAndLines() {
        val executor = MacCmsCatalogSourceExecutor(
            MacCmsSiteSpec("demo", "Demo", "https://api.test/provide/vod/", type = 1),
        ) { _, _, params -> if (params["ids"] != null) jsonDetail else json }

        val detail = executor.detail("11")
        assertEquals("简介", detail.content)
        assertEquals("演员", detail.actor)
        assertEquals("导演", detail.director)
        assertEquals(1, detail.lines.size)
        assertEquals(2, detail.lines[0].episodes.size)
    }
}
