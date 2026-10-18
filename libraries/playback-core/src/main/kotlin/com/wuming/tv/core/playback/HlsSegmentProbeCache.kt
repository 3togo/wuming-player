package com.wuming.tv.core.playback

/** Shared by sequential detectors within one playlist preparation only.
 * The owning preparation supplies the headers, cancellation and network budget.
 * Cache complete URLs, including queries; never share results across preparations. */
class HlsSegmentProbeCache(private val delegate: HlsResolutionAdFilter.Probe) : HlsResolutionAdFilter.Probe {
    private data class Outcome(val info: HlsResolutionAdFilter.VideoInfo? = null, val failure: Exception? = null)

    private val results = HashMap<String, Outcome>()

    override fun read(url: String): HlsResolutionAdFilter.VideoInfo? {
        val outcome = results.getOrPut(url) {
            try {
                Outcome(delegate.read(url)?.takeIf { it.valid() })
            } catch (error: Exception) {
                Outcome(failure = error)
            }
        }
        outcome.failure?.let { throw it }
        return outcome.info
    }
}
