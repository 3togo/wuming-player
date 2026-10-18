package com.wuming.android.tv.player.exo;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

/**
 * Verifies the device-capability gates added to {@link ExoUtil} for hardware-decode rendering:
 * tunneling is only requested when a video decoder actually advertises it. The frame-rate
 * strategy set in {@code ExoUtil.buildPlayer} has no public getter, so it is covered by the
 * green build + the manual {@code adb logcat} check, not by a unit assertion.
 */
@RunWith(AndroidJUnit4.class)
public class ExoUtilInstrumentedTest {

    @Test
    public void tunnelingCapabilityMatchesDecoderScan() {
        // Independent oracle: replicate ExoUtil.supportsTunnelingVideo()'s decision with a direct
        // MediaCodecList scan. This fails if the gate ever disagrees with the device's real decoder
        // capabilities (e.g. a decoder starts/stops reporting FEATURE_TunneledPlayback, or the
        // min-API guard changes) -- so it is a real regression guard, not a tautology.
        boolean expected = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (info.isEncoder()) continue;
                for (String type : info.getSupportedTypes()) {
                    if (!type.startsWith("video/")) continue;
                    try {
                        MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
                        if (caps != null && caps.isFeatureSupported(
                                MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback)) {
                            expected = true;
                            break;
                        }
                    } catch (Exception ignored) {
                        // Some decoders reject unsupported types; consistent with ExoUtil.
                    }
                }
                if (expected) break;
            }
        }
        assertEquals("ExoUtil.supportsTunnelingVideo() must agree with the device codec scan",
                expected, ExoUtil.supportsTunnelingVideo());
    }
}
