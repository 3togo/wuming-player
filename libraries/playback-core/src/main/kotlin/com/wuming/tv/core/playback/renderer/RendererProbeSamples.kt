package com.wuming.tv.core.playback.renderer

/** Small synthetic moving pattern + 440 Hz tone, bundled locally; no provider/network dependencies. */
object RendererProbeSamples {
    data class Sample(val file: String, val label: String, val capabilities: Set<RendererCapability>)
    val samples = listOf(
        Sample("avc.mp4", "MP4 · H.264 + AAC", setOf(RendererCapability.MP4, RendererCapability.H264, RendererCapability.AAC)),
        Sample("hls.m3u8", "HLS · H.264 + AAC", setOf(RendererCapability.HLS, RendererCapability.H264, RendererCapability.AAC)),
        Sample("dash.mpd", "DASH · H.264 + AAC", setOf(RendererCapability.DASH, RendererCapability.H264, RendererCapability.AAC)),
        Sample("hevc.mp4", "MP4 · HEVC + AAC", setOf(RendererCapability.MP4, RendererCapability.HEVC, RendererCapability.AAC)))
    fun resource(file: String): ByteArray? {
        if (!file.matches(Regex("[A-Za-z0-9_.-]{1,80}"))) return null
        return javaClass.getResourceAsStream("/renderer-probes/$file")?.use { it.readBytes() }
    }
    fun mime(file: String): String = when (file.substringAfterLast('.')) {
        "m3u8" -> "application/vnd.apple.mpegurl"
        "mpd" -> "application/dash+xml"
        "ts" -> "video/mp2t"
        "m4s" -> "video/iso.segment"
        else -> "video/mp4"
    }
    fun recordConfirmed(profiles: RendererProfiles, identity: RendererIdentity, sample: Sample, version: String, runId: String) {
        require(sample in samples)
        sample.capabilities.forEach { profiles.record(identity, RendererEvidence(it,
            EvidenceResult.CONTROLLED_SUCCESS, runId, System.currentTimeMillis(), version.take(128), "synthetic_picture_and_tone_confirmed")) }
    }
}
