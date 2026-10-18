package com.wuming.tv.core.cloud

/** AliPan quality selection policy — 1080p ceiling, template-id ranking. */
object AliPanQualityPolicy : DefaultQualitySelectionPolicy() {
    override fun providerKind(): String = "alipan"

    override fun isSmartQuality(flag: String?): Boolean {
        if (flag == null) return false
        val value = flag.lowercase()
        return value.contains("阿里智") || value.contains("ali智") || value.contains("aliyun智")
            || value.contains("alipan智") || value.contains("ali smart") || value.contains("aliyun smart")
            || value.contains("alipan smart") || value.contains("smart") || value.contains("preview")
            || value.contains("live_transcoding")
    }

    fun toCandidates(taskList: com.google.gson.JsonArray): List<TranscodeCandidate> {
        val result = mutableListOf<TranscodeCandidate>()
        for (element in taskList) {
            if (!element.isJsonObject) continue
            val task = element.asJsonObject
            val status = CloudJson.string(task, "status")
            if (status != "finished") continue
            val templateId = CloudJson.string(task, "template_id")
            val (w, h) = dimensionsFor(templateId)
            result.add(TranscodeCandidate(
                url = CloudJson.string(task, "url"),
                width = w,
                height = h,
                resolutionLabel = templateId,
                accessible = true,
                raw = task,
            ))
        }
        return result
    }

    private fun dimensionsFor(templateId: String): Pair<Int, Int> {
        return when (templateId.lowercase()) {
            "ld", "360" -> Pair(480, 360)
            "sd", "480" -> Pair(640, 480)
            "hd", "720" -> Pair(1280, 720)
            "fhd", "1080" -> Pair(1920, 1080)
            else -> Pair(0, 0)
        }
    }
}