package com.wuming.tv.core.media

import com.wuming.tv.core.media.catalog.MediaManifest
import java.util.LinkedHashSet
import java.util.function.LongSupplier
import java.util.logging.Level
import java.util.logging.Logger

/**
 * One resolver session shared by play, cast, cache, download, and HLS proxy consumers.
 * It refreshes expiring targets and can advance to the next manifest source after failure.
 */
class OpenDrivePlaybackSession(
    val manifest: MediaManifest,
    private val resolvers: MediaResolverRegistry,
    private val clock: LongSupplier
) {
    private val failedSources = LinkedHashSet<String>()
    private var current: MediaResolverRegistry.Resolution? = null

    private val log = Logger.getLogger("OpenDrivePlaybackSession")

    constructor(manifest: MediaManifest, resolvers: MediaResolverRegistry) : this(manifest, resolvers, System::currentTimeMillis)

    @Synchronized
    fun target(): PlaybackTarget {
        val now = clock.asLong
        if (current == null || current!!.status != MediaResolverRegistry.Resolution.Status.RESOLVED
            || current!!.target!!.expiresAtEpochMs > 0
            && current!!.target!!.expiresAtEpochMs <= now + REFRESH_WINDOW_MS
        ) {
            val previous = current
            val refreshed = resolvers.resolve(manifest, failedSources)
            if (refreshed.status == MediaResolverRegistry.Resolution.Status.RESOLVED) {
                current = refreshed
            } else if (previous == null
                || previous.status != MediaResolverRegistry.Resolution.Status.RESOLVED
                || previous.target!!.expiresAtEpochMs > 0
                && previous.target.expiresAtEpochMs <= now
            ) {
                current = refreshed
            }
        }
        if (current!!.status != MediaResolverRegistry.Resolution.Status.RESOLVED) {
            throw IllegalStateException("OpenDrive resource cannot be played")
        }
        return current!!.target!!
    }

    /** Marks the current source failed and resolves the next ordered source. */
    @Synchronized
    fun failover(): PlaybackTarget {
        if (current != null && current!!.sourceFingerprint.isNotEmpty()) {
            failedSources.add(current!!.sourceFingerprint)
            log.log(Level.WARNING, "failover uri={0} failedSource={1} totalFailed={2}",
                arrayOf(manifest.uri, current!!.sourceFingerprint, failedSources.size))
        }
        current = null
        return target()
    }

    /** Re-resolves the current source, for example after a signed URL expires. */
    @Synchronized
    fun refresh(): PlaybackTarget {
        log.log(Level.INFO, "refresh uri={0}", manifest.uri)
        current = null
        return target()
    }

    fun manifest(): MediaManifest = manifest

    companion object {
        private const val REFRESH_WINDOW_MS = 30_000L
    }
}
