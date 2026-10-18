package com.wuming.tv.core.media.subtitle

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.wuming.tv.core.cloud.CloudJson
import java.io.IOException
import java.net.URI
import java.util.Locale

/** Assrt response policy shared by HTTP adapters on Android and desktop. */
object AssrtSubtitleCodec {
    const val ID = "assrt"

    @JvmStatic
    @Throws(IOException::class)
    fun parseSearch(json: String, page: Int): SubtitlePage {
        val root = success(json)
        val values = CloudJson.array(CloudJson.jsonObject(root, "sub"), "subs")
        val candidates = ArrayList<SubtitleCandidate>()
        for (element in values) {
            if (!element.isJsonObject) continue
            val item = element.asJsonObject
            val id = CloudJson.string(item, "id")
            if (id.isEmpty()) continue
            val title = first(CloudJson.string(item, "native_name"), CloudJson.string(item, "videoname"), id)
            val language = CloudJson.string(CloudJson.jsonObject(item, "lang"), "desc")
            val format = normalizeFormat(CloudJson.string(item, "subtype"))
            val score = CloudJson.integer(item, "vote_score")
            candidates.add(SubtitleCandidate(ID, id, title, language, format, null, false, score.coerceIn(0, 100)))
        }
        return SubtitlePage(candidates, page, if (candidates.size < 15) page else page + 1)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun parseDetail(json: String, candidate: SubtitleCandidate): SubtitleCandidate {
        val root = success(json)
        val values = CloudJson.array(CloudJson.jsonObject(root, "sub"), "subs")
        if (values.isEmpty || !values[0].isJsonObject) throw IOException("Assrt detail contained no subtitle")
        val item = values[0].asJsonObject
        for (element in CloudJson.array(item, "filelist")) {
            if (!element.isJsonObject) continue
            val file = element.asJsonObject
            val name = CloudJson.string(file, "f")
            val format = extension(name)
            val url = CloudJson.string(file, "url")
            if (format.isNotEmpty() && url.isNotEmpty()) {
                return resolved(candidate, first(name, candidate.title()), format, url, false)
            }
        }
        val filename = CloudJson.string(item, "filename")
        val url = CloudJson.string(item, "url")
        val format = extension(filename)
        if (url.isNotEmpty() && format.isNotEmpty()) {
            return resolved(candidate, first(filename, candidate.title()), format, url, false)
        }
        if (url.isNotEmpty() && filename.lowercase(Locale.ROOT).endsWith(".zip")) {
            return resolved(candidate, first(filename, candidate.title()), "zip", url, true)
        }
        throw IOException("Assrt detail has no supported direct or ZIP subtitle")
    }

    private fun resolved(source: SubtitleCandidate, title: String, format: String, url: String, archive: Boolean): SubtitleCandidate {
        try {
            val uri = URI.create(url)
            if (!"http".equals(uri.scheme, ignoreCase = true) && !"https".equals(uri.scheme, ignoreCase = true)) {
                throw IOException("Unsafe subtitle URL")
            }
            return SubtitleCandidate(ID, source.id(), title, source.language(), format, uri, archive, source.matchScore())
        } catch (error: IllegalArgumentException) {
            throw IOException("Invalid subtitle URL", error)
        }
    }

    private fun success(json: String): JsonObject {
        try {
            val parsed = JsonParser.parseString(json)
            if (!parsed.isJsonObject) throw IOException("Assrt response is not an object")
            val root = parsed.asJsonObject
            if (CloudJson.integer(root, "status") != 0) throw IOException("Assrt API error " + CloudJson.integer(root, "status"))
            return root
        } catch (error: JsonParseException) {
            throw IOException("Invalid Assrt JSON", error)
        }
    }

    private fun first(vararg values: String): String =
        values.firstOrNull { value -> !value.chars().allMatch { Character.isWhitespace(it) } } ?: ""

    private fun normalizeFormat(value: String): String {
        val lower = value.lowercase(Locale.ROOT)
        return when {
            lower.contains("srt") || lower.contains("subrip") -> "srt"
            lower.contains("ass") -> "ass"
            lower.contains("ssa") -> "ssa"
            lower.contains("vtt") -> "vtt"
            else -> ""
        }
    }

    private fun extension(value: String): String {
        val dot = value.lastIndexOf('.')
        if (dot < 0) return ""
        val extension = value.substring(dot + 1).lowercase(Locale.ROOT)
        return if (extension in listOf("srt", "ass", "ssa", "vtt", "sub")) extension else ""
    }
}
