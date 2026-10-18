package com.wuming.tv.core.source.config

import java.net.URL
import java.util.Locale

enum class SourceKind { VOD, LIVE }
enum class SourceOrigin { CURATED, SCREENED, LOCAL }

data class SourceBrowserEntry(
    val name: String,
    val url: String,
    val kind: SourceKind,
    val origin: SourceOrigin,
    val warehouse: Boolean = false,
) {
    val host: String get() = runCatching { URL(url).host }.getOrDefault("")
}

/** Shared merge and search rules for the Android and desktop source pickers. */
object SourceBrowser {
    @JvmStatic
    fun merge(curated: List<SourceBrowserEntry>, screened: List<SourceBrowserEntry>,
              local: List<SourceBrowserEntry> = emptyList()): List<SourceBrowserEntry> =
        (curated + screened + local).distinctBy { it.url }

    @JvmStatic
    fun filter(entries: List<SourceBrowserEntry>, query: String,
               kind: SourceKind? = null, origin: SourceOrigin? = null): List<SourceBrowserEntry> {
        val tokens = query.lowercase(Locale.ROOT).split(Regex("\\s+")).filter(String::isNotBlank)
        return entries.filter { entry ->
            (kind == null || entry.kind == kind) && (origin == null || entry.origin == origin) &&
                tokens.all { token -> token in entry.name.lowercase(Locale.ROOT) ||
                    (!ProtectedSourceUrl.hasCredential(entry.url) && token in entry.url.lowercase(Locale.ROOT)) }
        }
    }
}
