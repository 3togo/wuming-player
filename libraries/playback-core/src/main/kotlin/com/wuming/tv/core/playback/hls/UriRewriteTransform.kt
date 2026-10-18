package com.wuming.tv.core.playback.hls

import java.net.URI
import java.util.regex.Matcher
import java.util.regex.Pattern

class UriRewriteTransform : HlsTransform {
    override fun apply(
        content: String?,
        context: HlsTransformContext?,
    ): HlsTransformationResult {
        if (content == null || context == null) {
            return HlsTransformationResult(
                content ?: "",
                emptyList(),
                listOf("Content or context is null"),
                HlsConfidence.LOW,
            )
        }
        val base =
            try {
                URI.create(context.playlistUrl)
            } catch (error: IllegalArgumentException) {
                return HlsTransformationResult(
                    content,
                    emptyList(),
                    listOf("Playlist URL is invalid; URIs were not rewritten"),
                    HlsConfidence.LOW,
                )
            }
        val lines = Pattern.compile("\\r?\\n").split(content, -1)
        var changed = false
        val output = StringBuilder(content.length + 256)
        for ((i, original) in lines.withIndex()) {
            val line =
                if (original.startsWith("#")) {
                    rewriteAttributes(original, base, context)
                } else if (original
                        .trim {
                            it <= ' '
                        }.isEmpty()
                ) {
                    original
                } else {
                    map(
                        original.trim {
                            it <= ' '
                        },
                        base,
                        context,
                    )
                }
            changed = changed || line != original
            output.append(line)
            if (i + 1 < lines.size) output.append('\n')
        }
        return HlsTransformationResult(
            output.toString(),
            if (changed) listOf("Resolved and mapped HLS child URIs") else emptyList(),
            emptyList(),
            HlsConfidence.HIGH,
        )
    }

    companion object {
        private val ATTRIBUTE_URI = Pattern.compile("URI=\"([^\"]+)\"")

        private fun rewriteAttributes(
            line: String,
            base: URI,
            context: HlsTransformContext,
        ): String {
            val matcher = ATTRIBUTE_URI.matcher(line)
            val output = StringBuffer()
            while (matcher.find()) {
                matcher.appendReplacement(
                    output,
                    Matcher.quoteReplacement("URI=\"" + map(matcher.group(1), base, context) + "\""),
                )
            }
            matcher.appendTail(output)
            return output.toString()
        }

        private fun map(
            value: String,
            base: URI,
            context: HlsTransformContext,
        ): String {
            try {
                val resolved = base.resolve(value)
                if (!"http".equals(resolved.scheme, true) && !"https".equals(resolved.scheme, true)) return value
                val mapped = context.uriMapper.apply(resolved.toString())
                return if (mapped.isBlank()) value else mapped
            } catch (error: IllegalArgumentException) {
                return value
            }
        }
    }
}
