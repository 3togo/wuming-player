package com.wuming.tv.core.playback

import java.util.function.BiFunction

/** One preparation pipeline for Android phone/TV and desktop. Ports supply rules,
 * URI resolution, logging and bounded segment transport; decisions stay here. */
object HlsAdFilterEngine {
    class Result @JvmOverloads constructor(
        val content: String,
        val replacement: String?,
        val resolution: HlsResolutionAdFilter.Result,
        val insertion: HlsResolutionAdFilter.Result,
        val legacyFailure: String?,
        val appended: HlsResolutionAdFilter.Result = HlsResolutionAdFilter.Result(content, 0, 0),
    )

    @JvmStatic
    fun purify(base: String, ruleLocation: String, playlist: String, rules: List<HlsAdRule>,
               probe: HlsResolutionAdFilter.Probe?, resolver: BiFunction<String, String, String>): Result {
        var legacyFailure: String? = null
        val legacy = try {
            HlsPlaylistPurifier.purify(base, playlist, HlsAdFilterPolicy.firstMatchingPatterns(ruleLocation, rules)) {
                b, r -> resolver.apply(b, r)
            }
        } catch (error: RuntimeException) {
            legacyFailure = error.javaClass.simpleName
            null
        }
        val sharedProbe = probe?.let(::HlsSegmentProbeCache)
        val resolution = HlsResolutionAdFilter.filter(base, legacy ?: playlist, sharedProbe, resolver)
        val insertion = HlsTimestampInsertionFilter.filter(base, resolution.content, sharedProbe, resolver)
        val appended = HlsAppendedAdFilter.filter(base, insertion.content, sharedProbe, resolver)
        val replacement = when {
            appended.removedSegments > 0 -> appended.content
            insertion.removedSegments > 0 -> insertion.content
            resolution.removedSegments > 0 -> resolution.content
            else -> legacy
        }
        return Result(replacement ?: playlist, replacement, resolution, insertion, legacyFailure, appended)
    }
}
