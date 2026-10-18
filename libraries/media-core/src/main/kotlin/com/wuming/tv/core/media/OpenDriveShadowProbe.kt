package com.wuming.tv.core.media

import com.wuming.tv.core.media.catalog.MediaManifest

/**
 * Read-only comparison between the current playback input and the candidate resolver output.
 * It never writes the catalog and never changes the value passed to the existing player.
 */
class OpenDriveShadowProbe(
    private val decoders: InputDecoderRegistry,
    private val resolvers: MediaResolverRegistry
) {
    fun inspect(currentPlaybackInput: String?): Observation {
        val decoded = decoders.decode(currentPlaybackInput)
        if (decoded.status == DecodeResult.Status.NO_MATCH) return Observation.noMatch()
        if (decoded.status == DecodeResult.Status.INVALID) {
            return Observation.invalid(decoded.message)
        }
        val media = decoded.media!!
        val manifest = MediaManifest(OpenDriveUri.local(media.kind), media.title, 1, media.sources)
        val resolution = resolvers.resolve(manifest)
        if (resolution.status != MediaResolverRegistry.Resolution.Status.RESOLVED) {
            return Observation.unsupported()
        }
        val candidate = resolution.target!!
        return Observation(Status.RESOLVED, candidate,
            currentPlaybackInput == candidate.url, "")
    }

    class Observation(
        val status: Status,
        val candidate: PlaybackTarget?,
        val byteEquivalent: Boolean,
        val message: String
    ) {
        @JvmName("status")
        fun statusAccessor(): Status = status
        @JvmName("candidate")
        fun candidateAccessor(): PlaybackTarget? = candidate
        @JvmName("byteEquivalent")
        fun byteEquivalentAccessor(): Boolean = byteEquivalent
        @JvmName("message")
        fun messageAccessor(): String = message

        companion object {
            @JvmStatic
            fun noMatch(): Observation = Observation(Status.NO_MATCH, null, false, "")

            @JvmStatic
            fun invalid(message: String): Observation = Observation(Status.INVALID, null, false, message)

            @JvmStatic
            fun unsupported(): Observation = Observation(Status.UNSUPPORTED, null, false, "")
        }
    }

    enum class Status { RESOLVED, NO_MATCH, INVALID, UNSUPPORTED }
}
