package com.wuming.android.tv.player.exo;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.TrackGroup;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.MappingTrackSelector.MappedTrackInfo;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Constructor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class VideoCapabilitiesInstrumentedTest {
    private static Format video(int width) {
        return new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265)
                .setCodecs("hvc1.2.4.L153.90").setWidth(width).setHeight(2160).setFrameRate(60).build();
    }

    @Test
    public void retainsSavedResultsAcrossInstancesAndSystemChangesUntilExplicitRefresh() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences prefs = context.getSharedPreferences("capability_test", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        try {
            AtomicInteger calls = new AtomicInteger();
            VideoCapabilityCache.Query query = () -> { calls.incrementAndGet(); return C.FORMAT_EXCEEDS_CAPABILITIES; };
            VideoCapabilityCache first = new VideoCapabilityCache(prefs, "system-v1");
            assertEquals(C.FORMAT_EXCEEDS_CAPABILITIES, first.resolve(video(3840), query));
            // Commit waits for pending apply writes before constructing the next cache instance.
            prefs.edit().putBoolean("flush", true).commit();
            new VideoCapabilityCache(prefs, "system-v1").resolve(video(3840), query);
            assertEquals(1, calls.get());
            first.resolve(video(1920), query);
            assertEquals(2, calls.get());
            new VideoCapabilityCache(prefs, "system-v2").resolve(video(3840), query);
            assertEquals(2, calls.get());
            VideoCapabilityCache refreshed = new VideoCapabilityCache(prefs, "system-v2");
            assertTrue(refreshed.refresh() > 0);
            refreshed.resolve(video(3840), query);
            assertEquals(2, calls.get());
        } finally {
            prefs.edit().clear().commit();
        }
    }

    @Test
    public void rejectsVideoBeyondCapabilitiesBeforeSelection() throws Exception {
        try {
            CapabilityTrackSelector.validateVideoCapabilities(mapped(C.TRACK_TYPE_VIDEO,
                    new int[] {C.FORMAT_EXCEEDS_CAPABILITIES}, TrackGroupArray.EMPTY));
            fail("Unsupported video must be rejected");
        } catch (ExoPlaybackException expected) {
            assertEquals(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, expected.errorCode);
            String message = new ErrorMsgProvider().get(expected);
            assertTrue(message, message.contains("HEVC (H.265)"));
            assertTrue(message, message.contains("3840 × 2160"));
            assertTrue(message, message.contains("60.00 fps"));
            assertTrue(message, message.contains("hvc1.2.4.L153.90"));
        }
    }

    @Test
    public void allowsSupportedAlternativeAndAudioOnly() throws Exception {
        CapabilityTrackSelector.validateVideoCapabilities(mapped(C.TRACK_TYPE_VIDEO,
                new int[] {C.FORMAT_EXCEEDS_CAPABILITIES, C.FORMAT_HANDLED}, TrackGroupArray.EMPTY));
        CapabilityTrackSelector.validateVideoCapabilities(mapped(C.TRACK_TYPE_AUDIO,
                new int[] {C.FORMAT_HANDLED}, TrackGroupArray.EMPTY));
    }

    @Test
    public void rejectsUnmappedVideoInsteadOfPlayingOnlyAudio() throws Exception {
        try {
            CapabilityTrackSelector.validateVideoCapabilities(mapped(C.TRACK_TYPE_AUDIO,
                    new int[] {C.FORMAT_HANDLED}, new TrackGroupArray(new TrackGroup(video(3840)))));
            fail("Unmapped video must be rejected");
        } catch (ExoPlaybackException expected) {
            assertEquals(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, expected.errorCode);
            String message = new ErrorMsgProvider().get(expected);
            assertTrue(message, message.contains("HEVC (H.265)"));
            assertTrue(message, message.contains("3840 × 2160"));
            assertTrue(message, message.contains("60.00 fps"));
            assertTrue(message, message.contains("hvc1.2.4.L153.90"));
        }
    }

    @Test
    public void reportsConnectedDeviceDecoderSupport() throws Exception {
        androidx.media3.exoplayer.mediacodec.MediaCodecUtil.clearDecoderInfoCache();
        Format actual = new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265)
                .setCodecs("hvc1.2.6.L150.90").setWidth(3840).setHeight(2160).build();
        for (androidx.media3.exoplayer.mediacodec.MediaCodecInfo decoder :
                androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(
                        MimeTypes.VIDEO_H265, false, false)) {
            android.os.Bundle result = new android.os.Bundle();
            result.putString("stream", decoder.name
                    + " 4K Main10 unknown fps=" + decoder.isFormatSupported(ApplicationProvider.getApplicationContext(), actual)
                    + " 4K Main10 60 fps=" + decoder.isFormatSupported(
                            ApplicationProvider.getApplicationContext(), actual.buildUpon().setFrameRate(60).build()) + "\n");
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(2, result);
        }
    }

    @Test
    public void fallbackHonorsPolicyAndStillRejectsUnsupportedVideo() throws Exception {
        CapabilityTrackSelector.validateVideoCapabilities(mapped(C.TRACK_TYPE_VIDEO,
                new int[]{C.FORMAT_EXCEEDS_CAPABILITIES}, TrackGroupArray.EMPTY), true);
        assertFalse(CapabilityTrackSelector.canAttempt(C.FORMAT_EXCEEDS_CAPABILITIES, false));
        for (int support : new int[]{C.FORMAT_UNSUPPORTED_TYPE, C.FORMAT_UNSUPPORTED_SUBTYPE,
                C.FORMAT_UNSUPPORTED_DRM}) {
            assertFalse(CapabilityTrackSelector.canAttempt(support, true));
            assertThrows(ExoPlaybackException.class, () -> CapabilityTrackSelector.validateVideoCapabilities(
                    mapped(C.TRACK_TYPE_VIDEO, new int[]{support}, TrackGroupArray.EMPTY), true));
        }
    }

    @Test
    public void reportsFailingQuarkFormatCapabilities() throws Exception {
        for (android.media.MediaCodecInfo codec : new android.media.MediaCodecList(
                android.media.MediaCodecList.ALL_CODECS).getCodecInfos()) {
            if (codec.isEncoder()) continue;
            for (String mime : codec.getSupportedTypes()) {
                if (!MimeTypes.VIDEO_H265.equals(mime)) continue;
                android.media.MediaCodecInfo.CodecCapabilities caps = codec.getCapabilitiesForType(mime);
                android.media.MediaCodecInfo.VideoCapabilities videoCaps = caps.getVideoCapabilities();
                StringBuilder profiles = new StringBuilder();
                for (android.media.MediaCodecInfo.CodecProfileLevel pl : caps.profileLevels)
                    profiles.append(pl.profile).append(':').append(pl.level).append(' ');
                android.os.Bundle result = new android.os.Bundle();
                result.putString("stream", codec.getName() + " profiles=" + profiles
                        + " size4k=" + videoCaps.isSizeSupported(3840, 2160)
                        + " rate4k60=" + videoCaps.areSizeAndRateSupported(3840, 2160, 60)
                        + " bitrate=" + videoCaps.getBitrateRange() + "\n");
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(2, result);
            }
        }
        for (androidx.media3.exoplayer.mediacodec.MediaCodecInfo decoder :
                androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_H265, false, false)) {
            for (float fps : new float[]{30f, 60f, 60.000004f}) {
                Format format = video(3840).buildUpon().setFrameRate(fps).build();
                android.os.Bundle result = new android.os.Bundle();
                result.putString("stream", decoder.name + " hvc1.2.4.L153.90 fps=" + fps
                        + " supported=" + decoder.isFormatSupported(ApplicationProvider.getApplicationContext(), format) + "\n");
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(2, result);
            }
        }
    }

    private static MappedTrackInfo mapped(int type, int[] supports, TrackGroupArray unmapped) throws Exception {
        Format[] formats = new Format[supports.length];
        for (int i = 0; i < formats.length; i++) {
            formats[i] = type == C.TRACK_TYPE_VIDEO ? video(3840 / (i + 1))
                    : new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build();
        }
        Constructor<MappedTrackInfo> constructor = MappedTrackInfo.class.getDeclaredConstructor(
                String[].class, int[].class, TrackGroupArray[].class, int[].class, int[][][].class, TrackGroupArray.class);
        constructor.setAccessible(true);
        return constructor.newInstance(new String[] {"test"}, new int[] {type},
                new TrackGroupArray[] {new TrackGroupArray(new TrackGroup(formats))},
                new int[] {0}, new int[][][] {{supports}}, unmapped);
    }
}
