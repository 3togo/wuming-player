package com.wuming.android.tv.player.exo

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import com.wuming.tv.core.playback.VideoRenditionPolicy

/** Reject unsupported formats while honoring Media3's decoder-attempt policy. */
class CapabilityTrackSelector(
    context: Context,
) : DefaultTrackSelector(context) {
    private val playbackContext: Context = context

    @Throws(ExoPlaybackException::class)
    override fun selectAllTracks(
        definitions: Array<ExoTrackSelection.Definition?>,
        info: MappedTrackInfo,
        supports: Array<Array<IntArray>>,
        mixedMimeSupports: IntArray,
        parameters: Parameters,
    ) {
        validateVideoCapabilities(info, parameters.exceedRendererCapabilitiesIfNecessary, playbackContext)
        // Make HDR display rejection visible to automatic selection before it chooses
        // a rendition. Previously an HDR choice threw even when an SDR track existed.
        val filtered = copyMappedSupports(supports,
            IntArray(info.rendererCount) { info.getTrackGroups(it).length })
        val builder = parameters.buildUpon()
        for (renderer in 0 until info.rendererCount) {
            if (info.getRendererType(renderer) != C.TRACK_TYPE_VIDEO) continue
            val groups = info.getTrackGroups(renderer)
            for (group in 0 until groups.length) {
                for (track in 0 until groups[group].length) {
                    if (HdrDisplayChecker.shouldRejectHdrFormat(playbackContext, groups[group].getFormat(track))) {
                        filtered[renderer][group][track] = RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
                    }
                }
            }
            val positions = (0 until groups.length).flatMap { group ->
                (0 until groups[group].length).map { track -> group to track }
            }
            val candidates = positions.map { (group, track) ->
                when (RendererCapabilities.getFormatSupport(filtered[renderer][group][track])) {
                    C.FORMAT_HANDLED -> VideoRenditionPolicy.Support.SUPPORTED
                    C.FORMAT_EXCEEDS_CAPABILITIES -> VideoRenditionPolicy.Support.EXCEEDS
                    else -> VideoRenditionPolicy.Support.UNSUPPORTED
                }
            }
            val eligible = VideoRenditionPolicy.eligibleIndices(candidates,
                parameters.exceedRendererCapabilitiesIfNecessary).toSet()
            for ((index, position) in positions.withIndex()) {
                if (index !in eligible) filtered[renderer][position.first][position.second] =
                    RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
            }
            for (group in 0 until groups.length) {
                val tracks = groups[group]
                val savedOverride = parameters.overrides[tracks]
                if (savedOverride != null && savedOverride.trackIndices.any {
                        !canAttempt(RendererCapabilities.getFormatSupport(filtered[renderer][group][it]),
                            parameters.exceedRendererCapabilitiesIfNecessary)
                    }) builder.clearOverride(tracks)
            }
        }
        super.selectAllTracks(definitions, info, filtered, mixedMimeSupports, builder.build())
        // Saved overrides must honor the same support policy as automatic selection.
        for (renderer in definitions.indices) {
            val selection = definitions[renderer] ?: continue
            if (info.getRendererType(renderer) != C.TRACK_TYPE_VIDEO) continue
            val group = info.getTrackGroups(renderer).indexOf(selection.group)
            for (track in selection.tracks) {
                val fmt = selection.group.getFormat(track)
                val codecOk = canAttempt(info.getTrackSupport(renderer, group, track), parameters.exceedRendererCapabilitiesIfNecessary)
                val displayOk = !HdrDisplayChecker.shouldRejectHdrFormat(playbackContext, fmt)
                if (!codecOk || !displayOk) {
                    throw unsupported(fmt, info.getTrackSupport(renderer, group, track), !displayOk)
                }
            }
        }
    }

    companion object {
        // Media3 may keep spare null group slots in its backing arrays. Only the
        // groups reported by MappedTrackInfo are initialized and selectable.
        internal fun copyMappedSupports(
            supports: Array<Array<IntArray>>,
            groupCounts: IntArray,
        ): Array<Array<IntArray>> = Array(groupCounts.size) { renderer ->
            Array(groupCounts[renderer]) { group -> supports[renderer][group].clone() }
        }

        @JvmStatic
        @Throws(ExoPlaybackException::class)
        fun validateVideoCapabilities(info: MappedTrackInfo) {
            validateVideoCapabilities(info, false)
        }

        // EXCEEDS is advisory when fallback is enabled; unsupported type/subtype/DRM still fails.
        // Retain this distinction in both scans to preserve original-playback decoder fallback.
        @JvmStatic fun canAttempt(
            support: Int,
            allowExceeds: Boolean,
        ): Boolean = support == C.FORMAT_HANDLED || (allowExceeds && support == C.FORMAT_EXCEEDS_CAPABILITIES)

        @JvmStatic
        @JvmOverloads
        @Throws(ExoPlaybackException::class)
        fun validateVideoCapabilities(
            info: MappedTrackInfo,
            allowExceeds: Boolean,
            context: Context? = null,
        ) {
            var hasVideo = false
            var rejected: Format? = null
            var rejectedSupport = C.FORMAT_UNSUPPORTED_TYPE
            var hasSupportedVideo = false
            for (renderer in 0 until info.rendererCount) {
                if (info.getRendererType(renderer) != C.TRACK_TYPE_VIDEO) continue
                val groups = info.getTrackGroups(renderer)
                for (group in 0 until groups.length) {
                    for (track in 0 until groups[group].length) {
                        val fmt = groups[group].getFormat(track)
                        hasVideo = true
                        if (rejected == null) {
                            rejected = fmt
                            rejectedSupport = info.getTrackSupport(renderer, group, track)
                        }
                        val codecOk = canAttempt(info.getTrackSupport(renderer, group, track), allowExceeds)
                        // Legacy context-free callers validate decoder support only.
                        val displayOk = context == null || !HdrDisplayChecker.shouldRejectHdrFormat(context, fmt)
                        if (codecOk && displayOk) {
                            hasSupportedVideo = true
                        }
                    }
                }
            }
            val unmapped = info.unmappedTrackGroups
            for (group in 0 until unmapped.length) {
                if (unmapped[group].type == C.TRACK_TYPE_VIDEO) {
                    hasVideo = true
                    if (rejected == null) rejected = unmapped[group].getFormat(0)
                }
            }
            if (hasVideo && !hasSupportedVideo) {
                val rejectedFormat = rejected ?: throw unsupported(
                    Format.Builder().build(), rejectedSupport
                )
                throw unsupported(rejectedFormat, rejectedSupport,
                    context != null && HdrDisplayChecker.shouldRejectHdrFormat(context, rejectedFormat))
            }
        }

        @JvmStatic
        @Throws(ExoPlaybackException::class)
        fun validateVideoCapabilities(info: MappedTrackInfo, context: Context) {
            validateVideoCapabilities(info, false, context)
        }

        private fun unsupported(
            format: Format,
            support: Int,
            hdrRejected: Boolean = false,
        ): ExoPlaybackException =
            ExoPlaybackException.createForSource(
                UnsupportedVideoException(format, support, if (hdrRejected)
                    UnsupportedVideoException.Reason.HDR_DISPLAY else UnsupportedVideoException.Reason.DECODER),
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            )
    }
}
