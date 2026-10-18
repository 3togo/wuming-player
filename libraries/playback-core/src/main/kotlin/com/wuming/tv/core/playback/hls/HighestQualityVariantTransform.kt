package com.wuming.tv.core.playback.hls

import java.util.regex.Pattern

class HighestQualityVariantTransform : HlsTransform {
    override fun apply(
        content: String?,
        context: HlsTransformContext?,
    ): HlsTransformationResult {
        if (content == null || !content.contains("#EXT-X-STREAM-INF")) {
            return HlsTransformationResult.unchanged(content ?: "")
        }
        val lines = Pattern.compile("\\r?\\n").split(content, -1)
        val variants = ArrayList<Variant>()
        for (header in lines.indices) {
            if (!lines[header].startsWith("#EXT-X-STREAM-INF")) continue
            var uri = header + 1
            while (uri < lines.size && (
                    lines[uri]
                        .trim {
                            it <= ' '
                        }.isEmpty() || lines[uri].startsWith("#")
                )
            ) {
                uri++
            }
            if (uri >= lines.size) continue
            variants.add(Variant(header, uri, pixels(lines[header]), bandwidth(lines[header])))
        }
        if (variants.size < 2) return HlsTransformationResult.unchanged(content)
        val best =
            variants.maxWith(
                compareBy<Variant> {
                    it.pixels
                }.thenBy {
                    it.bandwidth
                },
            )
        val remove = BooleanArray(lines.size)
        for (variant in variants) {
            if (variant !== best) {
                remove[variant.header] = true
                remove[variant.uri] = true
            }
        }
        val output = StringBuilder(content.length)
        for (i in lines.indices) {
            if (remove[i]) continue
            if (output.isNotEmpty()) output.append('\n')
            output.append(lines[i])
        }
        return HlsTransformationResult(output.toString(), listOf("Selected highest-quality HLS variant"), emptyList(), HlsConfidence.HIGH)
    }

    private class Variant(
        val header: Int,
        val uri: Int,
        val pixels: Long,
        val bandwidth: Long,
    )

    companion object {
        private val RESOLUTION = Pattern.compile("(?:^|,)RESOLUTION=(\\d+)x(\\d+)(?:,|$)", Pattern.CASE_INSENSITIVE)
        private val BANDWIDTH = Pattern.compile("(?:^|,)BANDWIDTH=(\\d+)(?:,|$)", Pattern.CASE_INSENSITIVE)

        private fun pixels(line: String): Long {
            val matcher = RESOLUTION.matcher(attributes(line))
            if (!matcher.find()) return 0
            return try {
                Math.multiplyExact(matcher.group(1)!!.toLong(), matcher.group(2)!!.toLong())
            } catch (
                ignored: ArithmeticException,
            ) {
                0
            } catch (ignored: NumberFormatException) {
                0
            }
        }

        private fun bandwidth(line: String): Long {
            val matcher = BANDWIDTH.matcher(attributes(line))
            if (!matcher.find()) return 0
            return try {
                matcher.group(1)!!.toLong()
            } catch (ignored: NumberFormatException) {
                0
            }
        }

        private fun attributes(line: String): String = line.substringAfter(':', "")
    }
}
