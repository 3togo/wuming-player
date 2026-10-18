package com.wuming.tv.core.cloud

/** Quark quality selection policy — 1080p ceiling, pixel-area ranking. */
object QuarkQualityPolicy : DefaultQualitySelectionPolicy() {
    override fun providerKind(): String = "quark"

    override fun isSmartQuality(flag: String?): Boolean {
        if (flag == null) return false
        val value = flag.lowercase()
        return value.contains("夸父智") || value.contains("夸克智") || value.contains("quark智")
    }

    fun transcodeResolution(flag: String?): String {
        if (flag == null) return ""
        val value = flag.lowercase()
        if (isSmartQuality(flag)) return "auto"
        if (value.contains("quark普画")) return "low"
        return ""
    }

    fun toCandidates(videos: com.google.gson.JsonArray): List<TranscodeCandidate> {
        val result = mutableListOf<TranscodeCandidate>()
        for (element in videos) {
            if (!element.isJsonObject) continue
            val video = element.asJsonObject
            val info = CloudJson.jsonObject(video, "video_info")
            result.add(TranscodeCandidate(
                url = CloudJson.string(info, "url"),
                width = CloudJson.number(info, "width").toInt(),
                height = CloudJson.number(info, "height").toInt(),
                resolutionLabel = CloudJson.string(video, "resolution"),
                accessible = !video.has("accessable") || CloudJson.bool(video, "accessable"),
                raw = video,
            ))
        }
        return result
    }
}