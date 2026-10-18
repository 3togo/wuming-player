package com.wuming.android.tv.search

import com.wuming.tv.core.search.TitleAliasResolver
import com.wuming.tv.core.search.TitleIdentity
import com.github.catvod.net.OkHttp
import com.github.catvod.utils.Prefers
import okhttp3.Request

/** Android storage/HTTP ports for shared alias learning, scoring and parsing. */
class TitleAliasRepository internal constructor(remoteEnabled: Boolean) : TitleAliasResolver {
    private val shared = com.wuming.tv.core.search.TitleAliasRepository(remoteEnabled, TitleNormalizer.core(),
        object : com.wuming.tv.core.search.TitleAliasRepository.Store {
            override fun read(): String = Prefers.getString(com.wuming.tv.core.search.TitleAliasRepository.STORE_KEY, "[]") ?: "[]"
            override fun write(value: String) { Prefers.put(com.wuming.tv.core.search.TitleAliasRepository.STORE_KEY, value) }
        }, { url, userAgent ->
            val request = Request.Builder().url(url)
            if (userAgent != null) request.header("User-Agent", userAgent)
            OkHttp.client(com.wuming.tv.core.search.TitleAliasRepository.LOOKUP_TIMEOUT_MS)
                .newCall(request.build()).execute().use { response -> if (response.isSuccessful) response.body.string() else "" }
        })
    override fun resolve(query: String): TitleIdentity = shared.resolve(query)
    fun canonicalKey(query: String): String = shared.canonicalKey(query)
    fun learn(identity: TitleIdentity?) = shared.learn(identity)
    companion object {
        private val INSTANCE = TitleAliasRepository(true)
        @JvmStatic fun get(): TitleAliasRepository = INSTANCE
        @JvmStatic @JvmName("parseDouban") internal fun parseDouban(json: String, query: String): TitleIdentity = INSTANCE.shared.parseDouban(json, query)
        @JvmStatic @JvmName("parseWikidata") internal fun parseWikidata(searchJson: String, entityJson: String, query: String): TitleIdentity = INSTANCE.shared.parseWikidata(searchJson, entityJson, query)
    }
}
