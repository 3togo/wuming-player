package com.wuming.android.tv.utils;

import static org.junit.Assert.assertEquals;

import androidx.media3.exoplayer.scheduler.Requirements;

import org.junit.Test;

public class VideoNetworkPolicyTest {

    @Test
    public void downloadsRequireUnmeteredNetworkWhenMobileVideoIsDisabled() {
        assertEquals(Requirements.NETWORK_UNMETERED,
                VideoNetworkPolicy.downloadRequirementFlags(false));
    }

    @Test
    public void downloadsMayUseAnyNetworkOnlyAfterMobileVideoIsEnabled() {
        assertEquals(Requirements.NETWORK,
                VideoNetworkPolicy.downloadRequirementFlags(true));
    }
}
