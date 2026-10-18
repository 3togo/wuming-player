package com.wuming.tv.core.playback

import com.wuming.tv.core.playback.VideoRenditionPolicy.Support.*
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoRenditionPolicyTest {
    @Test fun compatibleAndUnknownChoicesExcludeExceedingAndRejectedVariants() {
        assertEquals(listOf(1, 3), VideoRenditionPolicy.eligibleIndices(
            listOf(UNSUPPORTED, SUPPORTED, EXCEEDS, UNKNOWN), true))
    }

    @Test fun preservesMeasuredExceedingFallbackOnlyWhenNecessary() {
        assertEquals(listOf(0), VideoRenditionPolicy.eligibleIndices(listOf(EXCEEDS, UNSUPPORTED), true))
        assertEquals(emptyList<Int>(), VideoRenditionPolicy.eligibleIndices(listOf(EXCEEDS), false))
        assertEquals(emptyList<Int>(), VideoRenditionPolicy.eligibleIndices(listOf(UNSUPPORTED), true))
    }

    @Test fun capabilityEvidenceIsSpecificToEachAttempt() {
        assertEquals(emptyList<Int>(), VideoRenditionPolicy.eligibleIndices(listOf(UNSUPPORTED), true))
        assertEquals(listOf(0), VideoRenditionPolicy.eligibleIndices(listOf(SUPPORTED), true))
    }
}
