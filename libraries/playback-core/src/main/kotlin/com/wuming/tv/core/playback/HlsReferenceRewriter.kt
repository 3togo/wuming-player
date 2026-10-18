package com.wuming.tv.core.playback

import java.util.function.BiFunction
import java.util.function.Function

/** HLS URI parsing and master/media classification shared by cast and desktop relays.
 * Host ports resolve transport locations and register the resulting relay references. */
object HlsReferenceRewriter {
    private val URI_ATTRIBUTE = Regex("URI=\"([^\"]+)\"")

    @JvmStatic
    fun rewrite(content: String?, resolve: Function<String, String?>,
                map: BiFunction<String, Boolean, String>): String {
        if (content.isNullOrEmpty()) return ""
        var nextIsManifest = false
        return content.lineSequence().joinToString("\n") { line ->
            when {
                line.startsWith('#') -> {
                    val attributeIsManifest = line.startsWith("#EXT-X-MEDIA:") || line.startsWith("#EXT-X-I-FRAME-STREAM-INF:")
                    val rewritten = URI_ATTRIBUTE.replace(line) { match ->
                        val original = match.groupValues[1]
                        val resolved = resolve.apply(original)
                        "URI=\"${if (resolved == null) original else map.apply(resolved, attributeIsManifest)}\""
                    }
                    if (line.startsWith("#EXT-X-STREAM-INF:")) nextIsManifest = true
                    rewritten
                }
                line.isNotBlank() -> {
                    val original = line.trim()
                    val resolved = resolve.apply(original)
                    val rewritten = if (resolved == null) original else map.apply(resolved, nextIsManifest)
                    nextIsManifest = false
                    rewritten
                }
                else -> line
            }
        }
    }
}
