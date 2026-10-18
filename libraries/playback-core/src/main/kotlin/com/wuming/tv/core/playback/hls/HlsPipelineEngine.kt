package com.wuming.tv.core.playback.hls

internal class HlsPipelineEngine(
    transforms: List<HlsTransform>?,
) {
    companion object {
        @JvmStatic
        fun fromArray(transforms: Array<out HlsTransform?>?): HlsPipelineEngine =
            HlsPipelineEngine(transforms?.filterNotNull())
    }

    private val transforms: List<HlsTransform> =
        if (transforms == null) java.util.List.of() else java.util.List.copyOf(transforms)

    fun transform(
        content: String?,
        context: HlsTransformContext?,
    ): HlsTransformationResult {
        if (content == null || !content.startsWith("#EXTM3U")) {
            return HlsTransformationResult(
                content,
                java.util.List.of(),
                java.util.List.of("Input is not an HLS playlist"),
                HlsConfidence.NONE,
            )
        }
        var current = content
        val changes = ArrayList<String>()
        val warnings = ArrayList<String>()
        var confidence = HlsConfidence.HIGH
        for (transform in transforms) {
            val result: HlsTransformationResult? =
                try {
                    transform.apply(current, context)
                } catch (error: RuntimeException) {
                    warnings.add("${transform.javaClass.simpleName} failed: ${error.javaClass.simpleName}")
                    confidence = HlsConfidence.min(confidence, HlsConfidence.LOW)
                    continue
                }
            if (result == null || !result.content.startsWith("#EXTM3U")) {
                warnings.add("${transform.javaClass.simpleName} returned an invalid playlist; previous content retained")
                confidence = HlsConfidence.min(confidence, HlsConfidence.LOW)
                continue
            }
            current = result.content
            changes.addAll(result.changes)
            warnings.addAll(result.warnings)
            confidence = HlsConfidence.min(confidence, result.confidence)
        }
        return HlsTransformationResult(current, changes, warnings, confidence)
    }
}
