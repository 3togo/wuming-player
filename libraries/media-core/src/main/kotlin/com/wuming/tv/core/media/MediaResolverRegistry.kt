package com.wuming.tv.core.media

import com.wuming.tv.core.media.catalog.MediaManifest
import java.util.LinkedHashMap

/** Selects adapter resolvers while preserving manifest source priority. */
class MediaResolverRegistry(resolvers: List<SourceResolver>?) {
    private val resolvers: Map<String, SourceResolver>

    init {
        val copy = LinkedHashMap<String, SourceResolver>()
        if (resolvers != null) {
            for (resolver in resolvers) copy[resolver.adapterId()] = resolver
        }
        this.resolvers = copy.toMap()
    }

    fun resolve(manifest: MediaManifest?): Resolution = resolve(manifest, emptySet())

    fun resolve(manifest: MediaManifest?, excludedFingerprints: Set<String>?): Resolution {
        if (manifest == null) return Resolution.notFound()
        val excluded = excludedFingerprints ?: emptySet()
        for (source in manifest.sources) {
            if (excluded.contains(source.fingerprint)) continue
            val resolver = resolvers[source.adapterId] ?: continue
            try {
                return Resolution.resolved(resolver.resolve(source), source.fingerprint)
            } catch (_: RuntimeException) {
                // Try the next source; one broken provider must not poison the resource.
            }
        }
        return Resolution.unsupported()
    }

    class Resolution(
        val status: Status,
        val target: PlaybackTarget?,
        val sourceFingerprint: String
    ) {
        enum class Status { RESOLVED, NOT_FOUND, UNSUPPORTED }

        @JvmName("status")
        fun statusAccessor(): Status = status
        @JvmName("target")
        fun targetAccessor(): PlaybackTarget? = target
        @JvmName("sourceFingerprint")
        fun sourceFingerprintAccessor(): String = sourceFingerprint

        companion object {
            @JvmStatic
            fun resolved(target: PlaybackTarget, sourceFingerprint: String): Resolution =
                Resolution(Status.RESOLVED, target, sourceFingerprint)

            @JvmStatic
            fun notFound(): Resolution = Resolution(Status.NOT_FOUND, null, "")

            @JvmStatic
            fun unsupported(): Resolution = Resolution(Status.UNSUPPORTED, null, "")
        }
    }
}
