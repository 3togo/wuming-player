package com.wuming.tv.desktop.ui

import com.wuming.tv.desktop.net.DesktopResourceLoader
import com.wuming.tv.core.search.SearchSuggestions
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.prefs.Preferences
import javax.swing.SwingUtilities

internal class DesktopSearchHistoryStore(
    private val preferences: Preferences,
) {
    init {
        // Remove the legacy value as well: its U+001F separators invalidate the entire
        // Java preferences XML on the next launch, including saved provider approvals.
        preferences.get(LEGACY_HISTORY_KEY, null)?.let { legacy ->
            if (preferences.get(HISTORY_KEY, null) == null) save(normalize(legacy.split('\u001f')))
            preferences.remove(LEGACY_HISTORY_KEY)
        }
    }

    fun recent(): List<String> = runCatching {
        normalize(JsonParser.parseString(preferences.get(HISTORY_KEY, "[]")).asJsonArray.mapNotNull {
            it.takeIf { value -> value.isJsonPrimitive && value.asJsonPrimitive.isString }?.asString
        })
    }.getOrDefault(emptyList())

    private fun normalize(items: List<String>): List<String> = items.map(String::trim)
        .filter(String::isNotEmpty).distinct().take(MAX_ITEMS)

    fun add(keyword: String): List<String> {
        val normalized = keyword.trim()
        if (normalized.isEmpty()) return recent()
        return (listOf(normalized) + recent().filterNot { it == normalized })
            .take(MAX_ITEMS)
            .let(::save)
    }

    fun remove(keyword: String): List<String> = save(recent().filterNot { it == keyword })

    private fun save(items: List<String>): List<String> {
        val retained = items.toMutableList()
        var encoded = Gson().toJson(retained)
        while (encoded.length > Preferences.MAX_VALUE_LENGTH && retained.isNotEmpty()) {
            retained.removeAt(retained.lastIndex)
            encoded = Gson().toJson(retained)
        }
        preferences.put(HISTORY_KEY, encoded)
        return retained
    }

    private companion object {
        const val HISTORY_KEY = "search-keyword-history-json"
        const val LEGACY_HISTORY_KEY = "search-keyword-history"
        const val MAX_ITEMS = 50
    }
}

internal class DesktopSearchWordClient(
    loader: DesktopResourceLoader = DesktopResourceLoader(maximumBytes = 512 * 1024, logRequests = false),
    private val loadSuggestions: (String) -> String = { loader.load(it).toString(Charsets.UTF_8) },
) : AutoCloseable {
    private val generation = AtomicInteger()
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "tvbox-search-suggestions").apply { isDaemon = true }
    }
    @Volatile private var closed = false

    fun requestSuggestions(keyword: String, callback: (List<String>) -> Unit) {
        val query = keyword.trim()
        val request = generation.incrementAndGet()
        if (closed) return
        callback(SearchSuggestions.local(query))
        if (query.isEmpty()) return
        try {
            executor.execute {
                val words = runCatching {
                    val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
                    parseWords(loadSuggestions(SUGGEST_URL + encoded))
                }.getOrDefault(emptyList())
                SwingUtilities.invokeLater {
                    if (!closed && request == generation.get()) callback(SearchSuggestions.merge(query, words))
                }
            }
        } catch (rejected: RejectedExecutionException) {
            // close() races with a keystroke; a missed suggestion must not kill the EDT.
        }
    }

    override fun close() {
        closed = true
        generation.incrementAndGet()
        executor.shutdownNow()
    }

    companion object {
        private const val SUGGEST_URL = "https://suggest.video.iqiyi.com/?if=mobile&key="

        internal fun parseWords(json: String): List<String> = SearchSuggestions.parseResponse(json)
    }
}
