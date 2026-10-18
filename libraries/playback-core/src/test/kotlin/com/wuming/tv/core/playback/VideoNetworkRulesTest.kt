package com.wuming.tv.core.playback

import org.junit.Assert.*
import org.junit.Test

class VideoNetworkRulesTest {
    @Test fun cellularPreferencesDoNotBlockWifiAndRoamingRequiresBothPreferences() {
        assertNull(VideoNetworkRules.blocked(false, false, false, false))
        assertEquals(VideoNetworkRules.Blocked.MOBILE_DATA, VideoNetworkRules.blocked(true, true, false, true))
        assertEquals(VideoNetworkRules.Blocked.ROAMING_DATA, VideoNetworkRules.blocked(true, true, true, false))
        assertNull(VideoNetworkRules.blocked(true, true, true, true))
        assertNull(VideoNetworkRules.warning(true, true, true, false))
        assertEquals(VideoNetworkRules.Warning.ROAMING_DATA, VideoNetworkRules.warning(true, true, true, true))
        assertEquals(VideoNetworkRules.Warning.MOBILE_DATA, VideoNetworkRules.warning(true, false, true, false))
    }
}
