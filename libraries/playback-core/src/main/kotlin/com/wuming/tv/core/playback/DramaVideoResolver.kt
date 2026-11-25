package com.wuming.tv.core.playback

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.util.Base64

/** Official video metadata protocol used by the AI drama catalogue; no cloud session is involved. */
class DramaVideoResolver(private val fetch: (String) -> String = DramaServiceMetadata.shared::fetch) {
    fun resolve(episode: String): String {
        require(EPISODE.matches(episode)) { "Invalid drama episode identifier" }
        val video = episode.substringAfter('/')
        return parse(fetch(video), video)
    }

    internal fun parse(body: String, video: String): String {
        if (body.isBlank()) throw IOException("The drama service returned an empty response. Try again later.")
        if (body.length > 2 * 1024 * 1024) throw IOException("Drama response exceeds size limit")
        return try {
            parseMetadata(body, video)
        } catch (failure: IOException) {
            throw failure
        } catch (_: RuntimeException) {
            // Parser messages can contain provider text, including signed media URLs or keys.
            throw IOException("The drama service returned invalid playback metadata. Try again later.")
        }
    }

    private fun parseMetadata(body: String, video: String): String {
        val response = JsonParser.parseString(body).asJsonObject
        if (response.get("code")?.asInt != 0) throw IOException("Drama metadata request failed")
        val data = response.getAsJsonObject("data")
        val entry = if (data?.has("video_model") == true) data else data?.getAsJsonObject("video_model_data")?.getAsJsonObject(video)
            ?: throw IOException("Drama episode unavailable")
        val rawModel = entry.get("video_model")
        val model = if (rawModel.isJsonObject) rawModel.asJsonObject else JsonParser.parseString(rawModel.asString).asJsonObject
        val renditions = model.getAsJsonArray("video_list") ?: throw IOException("Drama has no renditions")
        // Prefer AVC for TV compatibility. Invalid renditions must not mask another usable choice.
        val ordered = renditions.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject }.sortedByDescending {
            try {
                val meta = it.getAsJsonObject("video_meta")
                if (meta?.get("codec_type")?.asString in listOf("h264", "avc"))
                    meta?.get("vheight")?.asInt ?: 0 else -1
            } catch (_: RuntimeException) { -1 }
        }
        for (item in ordered) {
            val capability = rendition(item)
            if (capability != null) return capability
        }
        throw IOException("Drama has no supported CENC rendition")
    }

    private fun rendition(item: JsonObject): String? = try {
        val encryption = item.getAsJsonObject("encrypt_info")
        if (encryption.get("encryption_method").asString != "cenc-aes-ctr") null else {
            val raw = item.get("main_url").asString
            val uri = raw.toHttpUrl()
            val key = SpadeMetadata.key(encryption.get("spade_a").asString)
            CencPlaybackSessions.register(uri.toString(), MEDIA_HEADERS, key)
        }
    } catch (_: RuntimeException) { null }

    companion object {
        private val EPISODE = Regex("[0-9]{1,30}/[0-9]{1,30}")
        private val MEDIA_HEADERS = mapOf("User-Agent" to "Mozilla/5.0", "Referer" to "https://novel.snssdk.com/")
        @JvmStatic fun supports(api: String?, episode: String?) =
            api?.removePrefix("csp_")?.removeSuffix("Guard") == "AiDj" && episode != null && EPISODE.matches(episode)
        internal fun hex(value: String): ByteArray {
            require(Regex("[0-9a-fA-F]{32}").matches(value)) { "Invalid CENC identifier" }
            return value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
    }
}

/** Bounded decoder for the provider's metadata envelope, independent of Android and the JAR. */
internal object SpadeMetadata {
    fun key(value: String): ByteArray {
        require(value.length in 4..1024) { "Invalid drama encryption metadata" }
        val bytes = Base64.getDecoder().decode(value)
        require(bytes.size >= 3) { "Invalid drama encryption metadata" }
        val trailer = ((bytes[0].toInt() xor bytes[1].toInt() xor bytes[2].toInt()) and 255) - 48
        val size = bytes.size - trailer - 1
        require(trailer >= 1 && size >= 2 && size < bytes.size) { "Invalid drama encryption metadata" }
        for (direction in intArrayOf(-1, 1)) {
            val previous = intArrayOf(250, 85)
            val decoded = ByteArray(size)
            for (index in decoded.indices) {
                val input = bytes[index + 1].toInt() and 255
                val lane = index and 1
                decoded[index] = ((input xor previous[lane]) + direction * (21 + Integer.bitCount(index))).toByte()
                previous[lane] = input
            }
            val padding = (decoded[0].toInt() and 255).toChar().digitToIntOrNull(36) ?: continue
            val end = size - padding
            if (end != 33) continue
            val text = String(decoded, 1, 32, Charsets.US_ASCII)
            if (Regex("[0-9a-fA-F]{32}").matches(text)) return DramaVideoResolver.hex(text)
        }
        throw IllegalArgumentException("Unsupported drama encryption metadata")
    }
}
