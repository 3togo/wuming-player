package com.wuming.tv.core.playback.hls

@FunctionalInterface
fun interface HlsTransform {
    fun apply(content: String?, context: HlsTransformContext?): HlsTransformationResult
}
