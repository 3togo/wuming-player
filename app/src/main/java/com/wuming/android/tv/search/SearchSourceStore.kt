package com.wuming.android.tv.search

import com.github.catvod.utils.Prefers
import com.wuming.tv.core.search.SearchSourceSelectionStore

object SearchSourceStore {
    private val shared = SearchSourceSelectionStore(object : SearchSourceSelectionStore.Store {
        override fun read(): String = Prefers.getString(SearchSourceSelectionStore.KEY, "{}") ?: "{}"
        override fun write(json: String) { Prefers.put(SearchSourceSelectionStore.KEY, json) }
    })
    @JvmStatic fun get(api: String): HashMap<String, String>? = shared.get(api)
    @JvmStatic fun put(api: String, sources: Map<String, String>) = shared.put(api, sources)
    @JvmStatic fun put(api: String, siteKey: String, checked: Boolean) = shared.put(api, siteKey, checked)
}
