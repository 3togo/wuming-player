package com.wuming.tv.core.search

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** Host storage supplies JSON persistence; selection updates and snapshots are shared. */
class SearchSourceSelectionStore(private val store: Store) {
    interface Store { fun read(): String; fun write(json: String) }
    private val gson = Gson()
    private val type = object : TypeToken<HashMap<String, HashMap<String, String>>>() {}.type
    @Synchronized fun get(api: String): HashMap<String, String>? = read()[api]?.let(::HashMap)
    @Synchronized fun put(api: String, sources: Map<String, String>) = read().also {
        it[api] = HashMap(sources); store.write(gson.toJson(it))
    }.let { Unit }
    @Synchronized fun put(api: String, siteKey: String, checked: Boolean) {
        val sourcesByApi = read()
        val sources = sourcesByApi.computeIfAbsent(api) { HashMap() }
        if (checked) sources[siteKey] = "1" else sources.remove(siteKey)
        store.write(gson.toJson(sourcesByApi))
    }
    private fun read(): HashMap<String, HashMap<String, String>> = try { gson.fromJson(store.read(), type) ?: HashMap() }
        catch (_: Exception) { HashMap() }
    companion object { const val KEY = "sources_for_search" }
}
