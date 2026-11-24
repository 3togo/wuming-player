package com.wuming.tv.core.playback

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Official video metadata protocol used by the AI drama catalogue; no cloud session is involved. */
class DramaVideoResolver(private val fetch: (String) -> String = ::fetchMetadata) {
    fun resolve(episode: String): String {
        require(EPISODE.matches(episode)) { "Invalid drama episode identifier" }
        val video = episode.substringAfter('/')
        val query = linkedMapOf(
            "aid" to "8662", "app_name" to "novelread", "version_code" to "74332",
            "version_name" to "7.4.3.32", "channel" to "vivo_8662_64",
            "device_platform" to "android", "device_type" to "PGT-AN10", "os_version" to "12",
            "update_version_code" to "74332", "iid" to "1234567890123456",
            "device_id" to "1234567890123456", "cdid" to UUID.randomUUID().toString(),
            "video_id" to video, "video_id_type" to "0", "device_level" to "1",
            "need_all_video_definition" to "1", "need_mp4_align" to "0", "use_os_player" to "1",
            "video_platform" to "1024", "source" to "0", "from_video_id" to "",
            "_rticket" to System.currentTimeMillis().toString(),
        )
        val url = ENDPOINT.toHttpUrl().newBuilder().apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build().toString()
        return parse(fetch(url), video)
    }

    internal fun parse(body: String, video: String): String {
        require(body.length <= 2 * 1024 * 1024) { "Drama response exceeds size limit" }
        val response = JsonParser.parseString(body).asJsonObject
        require(response.get("code")?.asInt == 0) { "Drama metadata request failed" }
        val entry = response.getAsJsonObject("data")?.getAsJsonObject("video_model_data")?.getAsJsonObject(video)
            ?: throw IOException("Drama episode unavailable")
        val model = JsonParser.parseString(entry.get("video_model").asString).asJsonObject
        val renditions = model.getAsJsonArray("video_list") ?: throw IOException("Drama has no renditions")
        // Prefer AVC for TV compatibility. Invalid renditions must not mask another usable choice.
        val ordered = renditions.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject }.sortedByDescending {
            val meta = it.getAsJsonObject("video_meta")
            if (meta?.get("codec_type")?.asString in listOf("h264", "avc"))
                meta?.get("vheight")?.asInt ?: 0 else -1
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
        private const val ENDPOINT = "https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/video_tab/multi_video_model/v1/"
        private val MEDIA_HEADERS = mapOf("User-Agent" to "Mozilla/5.0", "Referer" to "https://novel.snssdk.com/")
        @JvmStatic fun supports(api: String?, episode: String?) =
            api?.removePrefix("csp_")?.removeSuffix("Guard") == "AiDj" && episode != null && EPISODE.matches(episode)
        private val client by lazy { OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build() }
        private fun fetchMetadata(url: String): String = client.newCall(Request.Builder().url(url)
            .header("User-Agent", "okhttp/4.12.0").build()).execute().use {
                if (!it.isSuccessful) throw IOException("Drama metadata HTTP ${it.code}")
                val source = it.body?.source() ?: throw IOException("Empty drama metadata")
                if (source.request(2L * 1024 * 1024 + 1)) throw IOException("Drama response exceeds size limit")
                source.readUtf8()
            }
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
