package com.wuming.tv.core.media

import java.util.Objects

/**
 * Converts an already resolved playback target into a durable OpenDrive identity when doing so
 * cannot change the bytes requested by the player.
 */
class OpenDriveIdentityCodec(
    private val pipeline: OpenDrivePipeline,
    private val shadowProbe: OpenDriveShadowProbe
) {
    /**
     * Returns a canonical URI only when the persisted resolver reproduces the exact URL and
     * headers. Empty means the current source remains transient and history must use its fallback.
     */
    fun encode(playbackUrl: String, headers: Map<String, String>?): String {
        val currentHeaders = headers?.toMap() ?: emptyMap()
        if (currentHeaders.isNotEmpty()) return ""
        val observation = shadowProbe.inspect(playbackUrl)
        if (observation.status != OpenDriveShadowProbe.Status.RESOLVED
            || !observation.byteEquivalent
            || !Objects.equals(currentHeaders, observation.candidate!!.headers)) return ""
        val prepared = pipeline.prepare(playbackUrl)
        if (prepared.status != OpenDrivePipeline.Result.Status.READY) return ""
        val candidate = prepared.target!!
        if (!Objects.equals(playbackUrl, candidate.url)
            || !Objects.equals(currentHeaders, candidate.headers)) return ""
        return prepared.uri.toString()
    }
}
