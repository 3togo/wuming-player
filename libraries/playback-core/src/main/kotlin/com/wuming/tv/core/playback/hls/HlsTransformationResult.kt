package com.wuming.tv.core.playback.hls

class HlsTransformationResult(
    content: String?,
    changes: List<String>?,
    warnings: List<String>?,
    confidence: HlsConfidence?
) {
    val content: String = content ?: ""
    val changes: List<String> = java.util.List.copyOf(changes ?: emptyList())
    val warnings: List<String> = java.util.List.copyOf(warnings ?: emptyList())
    val confidence: HlsConfidence = confidence ?: HlsConfidence.NONE

    fun content(): String = content
    fun changes(): List<String> = changes
    fun warnings(): List<String> = warnings
    fun confidence(): HlsConfidence = confidence
    fun changed(): Boolean = changed

    override fun equals(other: Any?): Boolean =
        other is HlsTransformationResult && content == other.content &&
        changes == other.changes &&
        warnings == other.warnings &&
        confidence == other.confidence

    override fun hashCode(): Int = listOf(content, changes, warnings, confidence).hashCode()

    override fun toString(): String = "HlsTransformationResult(content=$content, changes=$changes, warnings=$warnings, confidence=$confidence)"

    val changed: Boolean
        get() = changes.isNotEmpty()

    companion object {
        @JvmStatic
        fun unchanged(content: String): HlsTransformationResult =
            HlsTransformationResult(content, emptyList(), emptyList(), HlsConfidence.HIGH)
    }
}
