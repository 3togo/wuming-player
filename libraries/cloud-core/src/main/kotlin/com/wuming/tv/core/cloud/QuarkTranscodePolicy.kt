package com.wuming.tv.core.cloud

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.IOException
import java.util.Locale

object QuarkTranscodePolicy {

    @JvmStatic
    @Throws(IOException::class)
    fun selectTranscode(videos: JsonArray, requestedResolution: String): JsonObject {
        var best: JsonObject? = null
        var bestRank = -1
        val automatic = "auto".equals(requestedResolution, ignoreCase = true)
        for (element in videos) {
            if (!element.isJsonObject) continue
            val video = element.asJsonObject
            if (!CloudJson.validDownloadUrl(CloudJson.string(CloudJson.jsonObject(video, "video_info"), "url"))) continue
            val resolution = CloudJson.string(video, "resolution")
            if (!automatic && requestedResolution.equals(resolution, ignoreCase = true)) return video
            if (automatic && (video.has("accessable") && !CloudJson.bool(video, "accessable")
                    || !supportedSmartQuality(video))) continue
            val rank = if (automatic) smartTranscodeRank(video) else transcodeRank(resolution)
            if (best == null || (automatic && rank > bestRank)) {
                best = video
                bestRank = rank
            }
        }
        if (best != null) return best
        if (automatic) throw OpenDriveFailure("quark", OpenDriveFailure.Reason.TRANSCODE_UNAVAILABLE)
        throw IOException("Quark did not return a transcoded video")
    }

    @JvmStatic
    fun supportedSmartQuality(video: JsonObject): Boolean {
        val info = CloudJson.jsonObject(video, "video_info")
        val width = CloudJson.number(info, "width")
        val height = CloudJson.number(info, "height")
        if (width > 0 && height > 0) {
            val longer = maxOf(width, height)
            val shorter = minOf(width, height)
            return longer <= 1920 && shorter <= 1080
        }
        val resolution = CloudJson.string(video, "resolution")
        return setOf("super", "high", "normal", "low").contains(resolution.lowercase(Locale.ROOT))
    }

    @JvmStatic
    fun smartTranscodeRank(video: JsonObject): Int {
        val info = CloudJson.jsonObject(video, "video_info")
        val width = CloudJson.number(info, "width")
        val height = CloudJson.number(info, "height")
        if (width > 0 && height > 0) return (width * height).toInt()
        return when (CloudJson.string(video, "resolution").lowercase(Locale.ROOT)) {
            "super" -> 1920 * 1080
            "high" -> 1280 * 720
            "normal" -> 960 * 540
            else -> 480 * 270
        }
    }

    @JvmStatic
    fun transcodeRank(resolution: String): Int {
        return when (resolution.lowercase(Locale.ROOT)) {
            "4k" -> 6
            "2k" -> 5
            "super" -> 4
            "high" -> 3
            "normal" -> 2
            "low" -> 1
            else -> 0
        }
    }

    @JvmStatic
    fun transcodeResolution(flag: String?): String {
        if (flag == null) return ""
        val value = flag.lowercase(Locale.ROOT)
        if (value.contains("夸父智") || value.contains("夸克智")
            || value.contains("quark智")) return "auto"
        if (value.contains("quark普画")) return "low"
        return ""
    }
}
