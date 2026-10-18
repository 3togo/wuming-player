package com.wuming.tv.core.media

import com.wuming.tv.core.media.OpenDriveUri
import com.wuming.tv.core.media.catalog.MediaCatalogStore
import com.wuming.tv.core.media.catalog.MediaManifest
import java.util.Optional

/**
 * Compatibility facade for raw input -> stable URI -> playback target.
 */
class OpenDrivePipeline(
    private val decoders: InputDecoderRegistry,
    private val catalog: MediaCatalogStore,
    private val resolvers: MediaResolverRegistry
) {
    fun prepare(input: String?): Result {
        if (LegacyOpenDriveId.hasLegacyPrefix(input)) {
            try {
                LegacyOpenDriveId.parse(input)
                return Result.legacy()
            } catch (_: IllegalArgumentException) {
                return Result.invalid("Legacy OpenDrive resource ID is invalid")
            }
        }
        if (OpenDriveUri.hasScheme(input)) {
            try {
                return resolve(OpenDriveUri.parse(input), false)
            } catch (_: IllegalArgumentException) {
                return Result.invalid("OpenDrive URI is invalid")
            }
        }

        val decoded = decoders.decode(input)
        if (decoded.status == DecodeResult.Status.NO_MATCH) return Result.unsupported()
        if (decoded.status == DecodeResult.Status.INVALID) return Result.invalid(decoded.message)

        val media = decoded.media!!
        if (media.persistable()) {
            val saved = catalog.save(media)
            return resolve(saved.uri, saved.created)
        }

        val transientManifest = MediaManifest(
            OpenDriveUri.local(media.kind), media.title, 1, media.sources)
        val resolution = resolvers.resolve(transientManifest)
        if (resolution.status != MediaResolverRegistry.Resolution.Status.RESOLVED) {
            return Result.unsupported()
        }
        return Result.transientTarget(resolution.target!!)
    }

    private fun resolve(uri: OpenDriveUri, created: Boolean): Result {
        val manifest = catalog.find(uri)
        if (manifest.isEmpty) return Result.notFound(uri)
        try {
            val target = OpenDrivePlaybackSession(manifest.get(), resolvers).target()
            return Result.ready(uri, target, created)
        } catch (_: IllegalStateException) {
            return Result.unsupported(uri)
        }
    }

    fun openSession(value: String?): Optional<OpenDrivePlaybackSession> {
        if (!OpenDriveUri.hasScheme(value)) return Optional.empty()
        try {
            val uri = OpenDriveUri.parse(value)
            return catalog.find(uri).map { manifest -> OpenDrivePlaybackSession(manifest, resolvers) }
        } catch (_: IllegalArgumentException) {
            return Optional.empty()
        }
    }

    class Result(
        val status: Status,
        val uri: OpenDriveUri?,
        val target: PlaybackTarget?,
        val created: Boolean,
        message: String?
    ) {
        val message: String = message ?: ""

        enum class Status { READY, TRANSIENT, LEGACY, INVALID, UNSUPPORTED, NOT_FOUND }

        init {
            if (status == Status.READY && (uri == null || target == null)) {
                throw IllegalArgumentException("Ready media requires a URI and target")
            }
            if (status == Status.TRANSIENT && (uri != null || target == null)) {
                throw IllegalArgumentException("Transient media requires only a target")
            }
            if (status != Status.READY && status != Status.TRANSIENT && target != null) {
                throw IllegalArgumentException("Non-ready media cannot contain a target")
            }
        }

        @JvmName("status")
        fun statusAccessor(): Status = status
        @JvmName("uri")
        fun uriAccessor(): OpenDriveUri? = uri
        @JvmName("target")
        fun targetAccessor(): PlaybackTarget? = target
        @JvmName("created")
        fun createdAccessor(): Boolean = created
        @JvmName("message")
        fun messageAccessor(): String = message

        companion object {
            @JvmStatic
            fun ready(uri: OpenDriveUri, target: PlaybackTarget, created: Boolean): Result =
                Result(Status.READY, uri, target, created, "")

            @JvmStatic
            fun transientTarget(target: PlaybackTarget): Result =
                Result(Status.TRANSIENT, null, target, false, "")

            @JvmStatic
            fun legacy(): Result = Result(Status.LEGACY, null, null, false, "")

            @JvmStatic
            fun invalid(message: String): Result = Result(Status.INVALID, null, null, false, message)

            @JvmStatic
            fun unsupported(): Result = unsupported(null)

            @JvmStatic
            fun unsupported(uri: OpenDriveUri?): Result = Result(Status.UNSUPPORTED, uri, null, false, "")

            @JvmStatic
            fun notFound(uri: OpenDriveUri): Result = Result(Status.NOT_FOUND, uri, null, false, "")
        }
    }
}
