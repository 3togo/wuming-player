package com.wuming.tv.core.libvio

import com.google.gson.Gson
import com.wuming.tv.core.catalog.*

/** Android's legacy JSON boundary; all catalogue and playback decisions remain in the executor. */
object LibvioCatVodProtocol {
    private val gson = Gson()
    fun home(home: CatalogHome): String = gson.toJson(mapOf(
        "class" to home.types.map { mapOf("type_id" to it.id, "type_name" to it.name) },
        "list" to home.items.map(::item),
    ))
    fun page(page: CatalogPage): String = gson.toJson(mapOf("list" to page.items.map(::item), "page" to page.page,
        "pagecount" to page.pageCount, "total" to page.total))
    fun items(items: List<CatalogItem>): String = gson.toJson(mapOf("list" to items.map(::item)))
    fun detail(detail: CatalogDetail): String {
        val value = item(detail.item).toMutableMap()
        value["vod_content"] = detail.content
        value["vod_actor"] = detail.actor
        value["vod_director"] = detail.director
        value["vod_play_from"] = detail.lines.joinToString("$$$") { clean(it.name) }
        value["vod_play_url"] = detail.lines.joinToString("$$$") { line ->
            line.episodes.joinToString("#") { "${clean(it.name)}$${it.id}" }
        }
        return gson.toJson(mapOf("list" to listOf(value)))
    }
    fun playback(value: CatalogPlayback): String = gson.toJson(mapOf("parse" to 0, "url" to value.url, "header" to value.headers))
    private fun item(value: CatalogItem) = mapOf("vod_id" to value.id, "vod_name" to value.name,
        "vod_pic" to value.picture, "vod_remarks" to value.remarks, "vod_year" to value.year)
    private fun clean(value: String) = value.replace('$', ' ').replace('#', ' ')
}
