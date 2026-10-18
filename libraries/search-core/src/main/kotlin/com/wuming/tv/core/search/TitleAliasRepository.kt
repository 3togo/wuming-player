package com.wuming.tv.core.search

import com.wuming.tv.core.search.TitleAliasResolver
import com.wuming.tv.core.search.TitleIdentity
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.regex.Pattern

/**
 * Resolves localized title aliases and remembers successful resolutions.
 *
 * English lookups use media catalogues to find proper Chinese titles rather
 * than literal translations. The network lookup is bounded and failures simply
 * fall back to the original English search.
 */
class TitleAliasRepository(private val remoteEnabled: Boolean, private val normalizer: TitleNormalizer,
                           private val store: Store, private val http: HttpPort) : TitleAliasResolver {
    interface Store { fun read(): String; fun write(value: String) }
    fun interface HttpPort { fun read(url: String, userAgent: String?): String }
    private val YEAR = Pattern.compile("\\b(?:18|19|20)\\d{2}\\b")
    private val GSON = Gson()
    private val STORED_TYPE: Type = object : TypeToken<List<StoredIdentity>>() {}.type
    private val byAlias: MutableMap<String, TitleIdentity> = HashMap()
    private val negativeCache: MutableSet<String> = HashSet()

    init {
        load()
        registerBuiltInAliases()
    }

    override fun resolve(query: String): TitleIdentity {
        val key = normalizer.key(query)
        if (key.isEmpty()) return TitleIdentity.empty()
        val cached = byAlias[key]
        if (cached != null) return cached
        if (!remoteEnabled || !normalizer.containsLatin(query) || key in negativeCache) {
            return TitleIdentity.empty()
        }
        var resolved = lookupDouban(query)
        if (resolved.isEmpty()) resolved = lookupWikidata(query)
        if (resolved.isEmpty()) {
            negativeCache.add(key)
            return resolved
        }
        register(resolved.withAlias(query), true)
        return byAlias.getOrDefault(key, resolved)
    }

    @Synchronized
    fun canonicalKey(query: String): String {
        val identity = byAlias[normalizer.key(query)]
        return if (identity != null && identity.canonicalId().isNotEmpty())
            "id:" + identity.canonicalId()
        else normalizer.key(query)
    }

    @Synchronized
    fun learn(identity: TitleIdentity?) {
        if (identity != null && !identity.isEmpty()) register(identity, true)
    }

    private fun lookupDouban(query: String): TitleIdentity {
        return try {
            val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8)
            val url = "https://movie.douban.com/j/subject_suggest?q=$encoded"
            parseDouban(http.read(url, null), query)
        } catch (e: Exception) {
            TitleIdentity.empty()
        }
    }

    private fun lookupWikidata(query: String): TitleIdentity {
        return try {
            val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8)
            val searchUrl = "https://www.wikidata.org/w/api.php?action=wbsearchentities" +
                "&search=$encoded" +
                "&language=en&uselang=zh-cn&type=item&limit=5&format=json&origin=*"
            val searchJson = request(searchUrl)
            val candidate = selectWikidataCandidate(searchJson, query) ?: return TitleIdentity.empty()
            val entityUrl = "https://www.wikidata.org/w/api.php?action=wbgetentities" +
                "&ids=" + URLEncoder.encode(candidate.id, StandardCharsets.UTF_8) +
                "&props=labels%7Caliases&languages=zh-cn%7Czh-hans%7Czh%7Cen" +
                "&languagefallback=1&format=json&origin=*"
            parseWikidataEntity(request(entityUrl), query, candidate)
        } catch (e: Exception) {
            TitleIdentity.empty()
        }
    }

    private fun request(url: String): String {
        return http.read(url, "TVBox title-alias lookup")
    }

    private fun registerBuiltInAliases() {
        register(
            TitleIdentity(
                "wikidata:Q207954",
                listOf("Pretty Woman", "风月俏佳人", "漂亮女人", "麻雀变凤凰"),
                "1990",
                "movie",
            ),
            false,
        )
    }

    private fun register(identity: TitleIdentity, persist: Boolean) {
        for (alias in identity.aliases()) {
            val key = normalizer.key(alias)
            if (key.isNotEmpty()) byAlias[key] = identity
        }
        if (persist) save()
    }

    private fun load() {
        try {
            val stored: List<StoredIdentity>? = GSON.fromJson(store.read(), STORED_TYPE)
            if (stored != null) {
                for (item in stored) register(item.toIdentity(), false)
            }
        } catch (e: Exception) {
            // SharedPreferences is unavailable in local JVM tests and early startup.
        }
    }

    private fun save() {
        try {
            val unique = LinkedHashMap<String, TitleIdentity>()
            for (identity in byAlias.values) {
                val key = if (identity.canonicalId().isEmpty())
                    normalizer.key(if (identity.aliases().isEmpty()) "" else identity.aliases()[0])
                else identity.canonicalId()
                unique[key] = identity
            }
            val stored = ArrayList<StoredIdentity>()
            for (identity in unique.values) stored.add(StoredIdentity.from(identity))
            store.write(GSON.toJson(stored))
        } catch (e: Exception) {
            // Alias lookup still works for this process if persistence is unavailable.
        }
    }

    private data class WikidataCandidate(val id: String, val description: String)

    private class StoredIdentity {
        var canonicalId: String? = null
        var aliases: List<String>? = null
        var year: String? = null
        var mediaType: String? = null

        fun toIdentity(): TitleIdentity = TitleIdentity(canonicalId, aliases, year, mediaType)

        companion object {
            fun from(identity: TitleIdentity): StoredIdentity = StoredIdentity().apply {
                canonicalId = identity.canonicalId()
                aliases = identity.aliases()
                year = identity.year()
                mediaType = identity.mediaType()
            }
        }
    }

        fun parseDouban(json: String, query: String): TitleIdentity = parseDoubanStatic(json, query)

        fun parseWikidata(searchJson: String, entityJson: String, query: String): TitleIdentity {
            val candidate = selectWikidataCandidate(searchJson, query) ?: return TitleIdentity.empty()
            return parseWikidataEntity(entityJson, query, candidate)
        }

        private fun parseDoubanStatic(json: String, query: String): TitleIdentity {
            return try {
                val array = JsonParser.parseString(json).asJsonArray
                if (array.isEmpty()) return TitleIdentity.empty()
                val item = array[0].asJsonObject
                val id = string(item, "id")
                val title = string(item, "title")
                val subtitle = string(item, "sub_title")
                val year = string(item, "year")
                val type = string(item, "type")
                val aliases = ArrayList<String>()
                aliases.add(query)
                if (title.isNotBlank()) aliases.add(title)
                if (subtitle.isNotBlank()) aliases.add(subtitle)
                if (title.isBlank() || !normalizer.containsHan(title)) return TitleIdentity.empty()
                TitleIdentity(if (id.isBlank()) "" else "douban:$id", aliases, year, type)
            } catch (e: Exception) {
                TitleIdentity.empty()
            }
        }

        private fun selectWikidataCandidate(json: String, query: String): WikidataCandidate? {
            return try {
                val results = JsonParser.parseString(json).asJsonObject.getAsJsonArray("search")
                var best: WikidataCandidate? = null
                var bestScore = Int.MIN_VALUE
                for (element in results) {
                    val item = element.asJsonObject
                    val id = string(item, "id")
                    val description = string(item, "description")
                    val match = if (item.has("match") && item.get("match").isJsonObject)
                        item.getAsJsonObject("match") else com.google.gson.JsonObject()
                    val matchText = string(match, "text")
                    val matchType = string(match, "type")
                    val score = wikidataScore(query, matchText, matchType, description)
                    if (id.isNotBlank() && score > bestScore) {
                        best = WikidataCandidate(id, description)
                        bestScore = score
                    }
                }
                if (bestScore >= 100) best else null
            } catch (e: Exception) {
                null
            }
        }

        private fun wikidataScore(query: String, matchText: String, matchType: String, description: String): Int {
            val normalizedDescription = description.lowercase(Locale.ROOT)
            val screenTitle = normalizedDescription.contains("film") ||
                normalizedDescription.contains("television series") ||
                normalizedDescription.contains("tv series") ||
                normalizedDescription.contains("miniseries") ||
                normalizedDescription.contains("episode")
            if (!screenTitle) return Int.MIN_VALUE
            var score = if (normalizer.equivalent(query, matchText)) 100 else 0
            if ("label" == matchType) score += 20
            if (normalizedDescription.contains("film")) score += 50
            else if (normalizedDescription.contains("series")) score += 40
            else if (normalizedDescription.contains("episode")) score += 10
            return score
        }

        private fun parseWikidataEntity(json: String, query: String, candidate: WikidataCandidate): TitleIdentity {
            return try {
                val entities = JsonParser.parseString(json).asJsonObject.getAsJsonObject("entities")
                val entity = entities.getAsJsonObject(candidate.id) ?: return TitleIdentity.empty()
                val aliases = ArrayList<String>()
                aliases.add(query)
                val aliasGroups = if (entity.has("aliases")) entity.getAsJsonObject("aliases") else com.google.gson.JsonObject()
                addChineseAliases(aliases, aliasGroups, "zh-cn")
                addChineseAliases(aliases, aliasGroups, "zh-hans")
                addChineseAliases(aliases, aliasGroups, "zh")
                val labels = if (entity.has("labels")) entity.getAsJsonObject("labels") else com.google.gson.JsonObject()
                addChineseLabel(aliases, labels, "zh-cn")
                addChineseLabel(aliases, labels, "zh-hans")
                addChineseLabel(aliases, labels, "zh")
                if (aliases.size == 1) return TitleIdentity.empty()
                val matcher = YEAR.matcher(candidate.description)
                val year = if (matcher.find()) matcher.group() else ""
                val description = candidate.description.lowercase(Locale.ROOT)
                val type = when {
                    description.contains("film") -> "movie"
                    description.contains("episode") -> "episode"
                    else -> "series"
                }
                TitleIdentity("wikidata:" + candidate.id, aliases, year, type)
            } catch (e: Exception) {
                TitleIdentity.empty()
            }
        }

        private fun addChineseAliases(result: MutableList<String>, groups: com.google.gson.JsonObject, language: String) {
            val element = groups[language]
            if (element == null || !element.isJsonArray) return
            for (alias in element.asJsonArray) {
                if (!alias.isJsonObject) continue
                addChinese(result, string(alias.asJsonObject, "value"))
            }
        }

        private fun addChineseLabel(result: MutableList<String>, labels: com.google.gson.JsonObject, language: String) {
            val element = labels[language]
            if (element != null && element.isJsonObject) {
                addChinese(result, string(element.asJsonObject, "value"))
            }
        }

        private fun addChinese(result: MutableList<String>, value: String) {
            val simplified = normalizer.simplified(value)
            if (normalizer.containsHan(simplified) && simplified !in result) result.add(simplified)
        }

        private fun string(`object`: com.google.gson.JsonObject, name: String): String {
            val element = `object`[name]
            return if (element == null || element.isJsonNull) "" else element.asString.trim()
        }
    companion object {
        const val STORE_KEY = "search_title_aliases_v2"
        const val LOOKUP_TIMEOUT_MS = 3500L
    }
}
