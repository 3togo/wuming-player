package com.wuming.tv.core.playback.hls

/** Removes only rejected variant declarations, preserving keys, renditions and variable tags. */
object HlsVariantFilter {
    fun filter(content: String, allowed: (String) -> Boolean): String {
        val lines = content.split('\n')
        val removed = mutableSetOf<Int>()
        for (header in lines.indices) {
            if (!lines[header].trim().startsWith("#EXT-X-STREAM-INF:")) continue
            var uri = header + 1
            while (uri < lines.size && (lines[uri].isBlank() || lines[uri].trim().startsWith("#"))) uri++
            if (uri < lines.size && !allowed(lines[uri].trim())) {
                removed.add(header)
                removed.add(uri)
            }
        }
        return lines.filterIndexed { index, _ -> index !in removed }.joinToString("\n")
    }
}
