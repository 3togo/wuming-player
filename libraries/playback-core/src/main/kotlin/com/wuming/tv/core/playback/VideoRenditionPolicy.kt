package com.wuming.tv.core.playback

/** Platform ports supply decoder/display evidence; uncertain metadata is not a rejection. */
object VideoRenditionPolicy {
    enum class Support { SUPPORTED, UNKNOWN, EXCEEDS, UNSUPPORTED }

    @JvmStatic fun eligibleIndices(support: List<Support>, allowExceeds: Boolean): List<Int> {
        val preferred = support.indices.filter { support[it] == Support.SUPPORTED || support[it] == Support.UNKNOWN }
        return if (preferred.isNotEmpty()) preferred else support.indices.filter {
            allowExceeds && support[it] == Support.EXCEEDS
        }
    }
}
