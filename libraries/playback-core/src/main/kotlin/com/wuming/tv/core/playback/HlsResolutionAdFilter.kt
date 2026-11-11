package com.wuming.tv.core.playback

import java.util.function.BiFunction

/**
 * Additional pass over existing rules. A sudden resolution change lasting under
 * ten minutes selects a candidate. Confirmation additionally requires an enclosed
 * format discontinuity, a PTS reset, and the programme timeline resuming.
 */
object HlsResolutionAdFilter {
    private const val MAX_RESOLUTION_CHANGE_SECONDS = 600.0
    private const val MAX_TIMESTAMP_INSERTION_SECONDS = 30.0
    private const val MAX_CANDIDATE_SAMPLES = 16
    private const val MIN_PROGRAMME_SECONDS = 60.0

    class VideoInfo @JvmOverloads constructor(
        val width: Int, val height: Int, val ptsSeconds: Double,
        /** Progressive AVC VUI timing rate; zero means unknown, not measured FPS. */
        val nominalFrameRate: Double = 0.0,
    ) {
        @JvmName("width")
        fun widthAccessor(): Int = width
        @JvmName("height")
        fun heightAccessor(): Int = height
        @JvmName("ptsSeconds")
        fun ptsSecondsAccessor(): Double = ptsSeconds

        fun valid(): Boolean = width > 0 && height > 0 && ptsSeconds.isFinite() && ptsSeconds >= 0
        fun sameResolution(other: VideoInfo): Boolean = width == other.width && height == other.height
    }

    fun interface Probe {
        @Throws(Exception::class)
        fun read(url: String): VideoInfo?
    }

    class Result(val content: String, val removedBlocks: Int, val removedSegments: Int) {
        @JvmName("content")
        fun contentAccessor(): String = content
        @JvmName("removedBlocks")
        fun removedBlocksAccessor(): Int = removedBlocks
        @JvmName("removedSegments")
        fun removedSegmentsAccessor(): Int = removedSegments
    }

    private class Segment(val url: String, val duration: Double)
    private class Block {
        val lines: MutableList<String> = ArrayList()
        val segments: MutableList<Segment> = ArrayList()
        var duration: Double = 0.0
    }

    @JvmStatic
    fun filter(base: String, playlist: String?, probe: Probe?, resolver: BiFunction<String, String, String>): Result {
        return filterInternal(base, playlist, probe, resolver, false)
    }

    internal fun filterInternal(base: String, playlist: String?, probe: Probe?,
                                resolver: BiFunction<String, String, String>, timestampInsertion: Boolean,
                                appended: Boolean = false): Result {
        val unchanged = Result(playlist ?: "", 0, 0)
        if (probe == null || playlist == null || !playlist.startsWith("#EXTM3U") || !playlist.contains("#EXT-X-ENDLIST")) return unchanged
        for (tag in listOf("#EXT-X-KEY", "#EXT-X-MAP", "#EXT-X-BYTERANGE",
                "#EXT-X-GAP", "#EXT-X-PART", "#EXT-X-SKIP", "#EXT-X-STREAM-INF",
                "#EXT-X-I-FRAMES-ONLY", "#EXT-X-DATERANGE")) {
            if (playlist.contains(tag)) return unchanged
        }
        try {
            val blocks = parse(base, playlist) { b, r -> resolver.apply(b, r) }
            if (appended) {
                val result = removeAppended(playlist, blocks, probe)
                if (result.removedSegments == 0) return result
                // Android publishes replacements at a different local URL. As in
                // the enclosed passes, resolve programme URIs before publishing.
                val content = result.content.lineSequence().joinToString("\n") {
                    if (it.isBlank() || it.startsWith('#')) it else resolver.apply(base, it.trim())
                }
                return Result(content, result.removedBlocks, result.removedSegments)
            }
            val cache = HashMap<String, VideoInfo?>()
            val targetDuration = playlist.lineSequence().firstOrNull { it.startsWith("#EXT-X-TARGETDURATION:") }
                ?.substringAfter(":")?.trim()?.toDoubleOrNull() ?: 0.0
            val totalDuration = blocks.sumOf { it.duration }
            var precedingDuration = 0.0
            var removedBlocks = 0
            var removedSegments = 0
            val output = StringBuilder()
            for (i in blocks.indices) {
                val block = blocks[i]
                val remove = i > 0 && i + 1 < blocks.size
                    && block.duration > 0
                    && (if (timestampInsertion) block.duration <= MAX_TIMESTAMP_INSERTION_SECONDS
                        else block.duration < MAX_RESOLUTION_CHANGE_SECONDS)
                    && block.segments.isNotEmpty()
                    && (!timestampInsertion || block.segments.size <= 15)
                    && (if (timestampInsertion) {
                        // A separately encoded insertion ends with a truncated segment.
                        // Ordinary discontinuities alone must not spend the probe budget.
                        targetDuration.isFinite() && targetDuration > 0
                            && block.segments.last().duration < targetDuration - 0.01
                            && precedingDuration >= MIN_PROGRAMME_SECONDS
                            && totalDuration - precedingDuration - block.duration >= MIN_PROGRAMME_SECONDS
                    } else blocks[i - 1].duration >= MIN_PROGRAMME_SECONDS
                        && blocks[i + 1].duration >= MIN_PROGRAMME_SECONDS)
                    && confirmed(blocks[i - 1], block, blocks[i + 1], probe, cache, timestampInsertion)
                precedingDuration += block.duration
                if (remove) {
                    removedBlocks++
                    removedSegments += block.segments.size
                } else {
                    for (line in block.lines) output.append(line).append('\n')
                }
            }
            if (removedBlocks == 0) return unchanged
            val absolute = StringBuilder()
            for (line in output.toString().split("\n")) {
                absolute.append(if (line.isBlank() || line.startsWith("#")) line else resolver.apply(base, line.trim())).append('\n')
            }
            return Result(absolute.toString(), removedBlocks, removedSegments)
        } catch (_: RuntimeException) {
            return unchanged
        }
    }

    /** Tail confirmation differs from enclosed insertions: no programme return
     * exists. Keep this conjunction aligned with docs/hls-ad-filtering.md. */
    private fun removeAppended(playlist: String, blocks: List<Block>, probe: Probe): Result {
        val unchanged = Result(playlist, 0, 0)
        if (blocks.size < 2 || playlist.lineSequence().lastOrNull { it.isNotBlank() } != "#EXT-X-ENDLIST") return unchanged
        val tail = blocks.last()
        val programme = blocks[blocks.lastIndex - 1]
        if (tail.duration <= 0 || tail.duration > MAX_TIMESTAMP_INSERTION_SECONDS ||
            tail.segments.size !in 2..15 || programme.duration < MIN_PROGRAMME_SECONDS ||
            programme.segments.size < 3) return unchanged
        val last = programme.segments.lastIndex
        val cadence = programme.segments[last - 1].duration
        // Independent segmentation: programme closes early, and the new encoder
        // starts a longer segment. A normal final short segment alone is retained.
        if (programme.segments[last].duration >= cadence - 0.01 ||
            tail.segments.first().duration <= cadence + 0.5) return unchanged
        var window = programme.segments[last].duration
        var anchor = last - 1
        while (anchor >= 0 && window - programme.segments[last].duration < MIN_PROGRAMME_SECONDS) {
            if (kotlin.math.abs(programme.segments[anchor].duration - cadence) > 0.01) return unchanged
            window += programme.segments[anchor].duration
            anchor--
        }
        anchor++
        if (window - programme.segments[last].duration < MIN_PROGRAMME_SECONDS) return unchanged
        try {
            val cache = HashMap<String, VideoInfo?>()
            val earlier = read(programme.segments[anchor], probe, cache) ?: return unchanged
            val preceding = read(programme.segments[last - 1], probe, cache) ?: return unchanged
            val end = read(programme.segments[last], probe, cache) ?: return unchanged
            fun knownRate(info: VideoInfo) = info.nominalFrameRate.isFinite() && info.nominalFrameRate in 10.0..120.0
            fun sameRate(a: VideoInfo, b: VideoInfo) = kotlin.math.abs(a.nominalFrameRate - b.nominalFrameRate) <= 0.1
            if (!knownRate(earlier) || !knownRate(preceding) || !knownRate(end) ||
                !earlier.sameResolution(end) || !preceding.sameResolution(end) ||
                !sameRate(earlier, end) || !sameRate(preceding, end) ||
                kotlin.math.abs(end.ptsSeconds - earlier.ptsSeconds - (window - programme.segments[last].duration)) > 0.5 ||
                kotlin.math.abs(end.ptsSeconds - preceding.ptsSeconds - cadence) > 0.5) return unchanged
            val first = read(tail.segments.first(), probe, cache) ?: return unchanged
            if (!knownRate(first) || kotlin.math.abs(first.nominalFrameRate - end.nominalFrameRate) < 1.0 ||
                first.ptsSeconds > 10 || end.ptsSeconds - first.ptsSeconds < 30) return unchanged
            var elapsed = 0.0
            for (segment in tail.segments) {
                val sample = read(segment, probe, cache) ?: return unchanged
                if (!knownRate(sample) || !first.sameResolution(sample) || !sameRate(first, sample) ||
                    kotlin.math.abs(sample.ptsSeconds - first.ptsSeconds - elapsed) > 0.5) return unchanged
                elapsed += segment.duration
            }
            // ENDLIST is in the removed block. Retain it, and every programme line.
            val output = blocks.dropLast(1).flatMap { it.lines }.joinToString("\n") + "\n#EXT-X-ENDLIST\n"
            return Result(output, 1, tail.segments.size)
        } catch (_: Exception) {
            return unchanged
        }
    }

    private fun confirmed(before: Block, ad: Block, after: Block, probe: Probe, cache: HashMap<String, VideoInfo?>, timestampInsertion: Boolean): Boolean {
        try {
            val previous = before.segments[before.segments.size - 1]
            val left = read(previous, probe, cache) ?: return false
            val first = read(ad.segments[0], probe, cache) ?: return false
            val right = read(after.segments[0], probe, cache) ?: return false
            if (!(if (timestampInsertion) left.sameResolution(first) else !left.sameResolution(first))) return false
            if (!left.sameResolution(right)) return false
            if (first.ptsSeconds > 10 || left.ptsSeconds - first.ptsSeconds < 30) return false
            if (kotlin.math.abs(right.ptsSeconds - (left.ptsSeconds + previous.duration)) > 0.5) return false
            var elapsed = 0.0
            val sampleIndices = if (timestampInsertion || ad.segments.size <= MAX_CANDIDATE_SAMPLES) {
                ad.segments.indices.toSet()
            } else {
                (0 until MAX_CANDIDATE_SAMPLES).map {
                    it.toLong().times(ad.segments.lastIndex).div(MAX_CANDIDATE_SAMPLES - 1).toInt()
                }.toSet()
            }
            for ((index, segment) in ad.segments.withIndex()) {
                if (index !in sampleIndices) {
                    elapsed += segment.duration
                    continue
                }
                val info = read(segment, probe, cache) ?: return false
                if (!(if (timestampInsertion) left.sameResolution(info) else !left.sameResolution(info)) || !first.sameResolution(info) || kotlin.math.abs(info.ptsSeconds - (first.ptsSeconds + elapsed)) > 0.5) return false
                elapsed += segment.duration
            }
            return true
        } catch (_: Exception) {
            return false
        }
    }

    private fun read(segment: Segment, probe: Probe, cache: HashMap<String, VideoInfo?>): VideoInfo? {
        if (!cache.containsKey(segment.url)) {
            val info = probe.read(segment.url)
            cache[segment.url] = if (info != null && info.valid()) info else null
        }
        return cache[segment.url]
    }

    private fun parse(base: String, playlist: String, resolver: (String, String) -> String): MutableList<Block> {
        val blocks = mutableListOf<Block>()
        var block = Block()
        blocks.add(block)
        var duration = -1.0
        for (raw in playlist.split(Regex("\\r?\\n"))) {
            val line = raw.trim()
            if (line == "#EXT-X-DISCONTINUITY") {
                if (duration >= 0) throw IllegalArgumentException("Incomplete segment")
                if (block.segments.isNotEmpty()) {
                    block = Block()
                    blocks.add(block)
                }
            } else if (line.startsWith("#EXTINF:")) {
                if (duration >= 0) throw IllegalArgumentException("Missing segment URI")
                duration = line.substring(8).split(",", limit = 2)[0].toDouble()
                if (!duration.isFinite() || duration <= 0) throw IllegalArgumentException("Invalid duration")
            } else if (line.isNotEmpty() && !line.startsWith("#")) {
                if (duration <= 0) throw IllegalArgumentException("Missing duration")
                block.segments.add(Segment(resolver(base, line), duration))
                block.duration += duration
                duration = -1.0
            }
            block.lines.add(line)
        }
        if (duration >= 0) throw IllegalArgumentException("Incomplete playlist")
        return blocks
    }
}
