package com.wuming.tv.core.cloud

/** UC quality selection policy — no explicit ceiling, max-width ranking. */
object UcQualityPolicy : DefaultQualitySelectionPolicy() {
    override fun providerKind(): String = "uc"

    override fun isSmartQuality(flag: String?): Boolean {
        if (flag == null) return false
        val value = flag.lowercase()
        return value.contains("uc智") || value.contains("优汐智") || value.contains("uc smart")
            || value.contains("ucpan smart") || value.contains("smart") || value.contains("preview")
    }

    override fun isWithinSmartBounds(candidate: TranscodeCandidate): Boolean = true

    override fun smartRank(candidate: TranscodeCandidate): Int {
        if (candidate.width > 0) return candidate.width
        return super.smartRank(candidate)
    }

    fun toCandidates(videoList: com.google.gson.JsonArray): List<TranscodeCandidate> {
        val result = mutableListOf<TranscodeCandidate>()
        for (element in videoList) {
            if (!element.isJsonObject) continue
            val video = element.asJsonObject
            val url = CloudJson.string(video, "url")
            if (url.isBlank() || !url.startsWith("https")) continue
            result.add(TranscodeCandidate(
                url = url,
                width = CloudJson.number(video, "width").toInt(),
                height = CloudJson.number(video, "height").toInt(),
                resolutionLabel = CloudJson.string(video, "resolution"),
                accessible = true,
                raw = video,
            ))
        }
        return result
    }
}