package com.wuming.android.tv.player.exo;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * JVM unit test for the hardware-decode tunnel gate in {@link ExoUtil}. The decision logic was
 * extracted into {@link ExoUtil#anyVideoDecoderSupportsTunneling(List)} so it can be exercised here
 * without a device (no MediaCodecList / MediaCodecInfo needed). The on-device agreement with the
 * real codec list is covered separately by ExoUtilInstrumentedTest.
 */
public class ExoUtilTest {

    private static ExoUtil.CodecTunnelSupport decoder(boolean tunneled, String... types) {
        return new ExoUtil.CodecTunnelSupport(false, Arrays.asList(types), tunneled);
    }

    @Test
    public void noCodecs_unsupported() {
        assertFalse(ExoUtil.anyVideoDecoderSupportsTunneling(Collections.emptyList()));
    }

    @Test
    public void encoderOnly_unsupported() {
        List<ExoUtil.CodecTunnelSupport> codecs = Arrays.asList(
                new ExoUtil.CodecTunnelSupport(true, Arrays.asList("video/avc"), true));
        assertFalse(ExoUtil.anyVideoDecoderSupportsTunneling(codecs));
    }

    @Test
    public void videoDecoderWithTunnelFeature_supported() {
        assertTrue(ExoUtil.anyVideoDecoderSupportsTunneling(Arrays.asList(decoder(true, "video/avc"))));
    }

    @Test
    public void videoDecoderWithoutTunnelFeature_unsupported() {
        assertFalse(ExoUtil.anyVideoDecoderSupportsTunneling(Arrays.asList(decoder(false, "video/avc"))));
    }

    @Test
    public void nonVideoDecoderIgnored() {
        assertFalse(ExoUtil.anyVideoDecoderSupportsTunneling(
                Arrays.asList(new ExoUtil.CodecTunnelSupport(false, Arrays.asList("audio/aac"), true))));
    }

    @Test
    public void mixedCodecs_supportedWhenAnyVideoDecoderHasFeature() {
        List<ExoUtil.CodecTunnelSupport> codecs = Arrays.asList(
                decoder(false, "video/avc"),                                             // video decoder, no tunnel
                new ExoUtil.CodecTunnelSupport(true, Arrays.asList("video/hevc"), true),  // encoder, must be ignored
                decoder(true, "video/hevc"));                                            // video decoder with tunnel
        assertTrue(ExoUtil.anyVideoDecoderSupportsTunneling(codecs));
    }
}
