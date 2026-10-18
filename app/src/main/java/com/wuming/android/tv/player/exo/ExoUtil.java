package com.wuming.android.tv.player.exo;
import com.wuming.android.tv.setting.PlayerSettings;

import android.content.Context;
import android.graphics.Color;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.CaptioningManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.TrackSelector;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.exoplayer.util.EventLogger;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.PlayerView;

import com.wuming.android.tv.App;
import com.wuming.android.tv.BuildConfig;
import com.wuming.android.tv.bean.Drm;
import com.wuming.android.tv.bean.Sub;
import com.wuming.android.tv.player.PlayerHelper;
import com.wuming.android.tv.player.engine.PlaySpec;
import com.wuming.android.tv.player.engine.PlayerEngine;
import com.wuming.android.tv.utils.UrlUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ExoUtil {

    public static void setPlayerView(PlayerView view) {
        view.getSubtitleView().setStyle(getCaptionStyle());
        view.getSubtitleView().setApplyEmbeddedStyles(true);
        view.getSubtitleView().setApplyEmbeddedFontSizes(false);
        if (PlayerSettings.getSubtitleTextSize() != 0) view.getSubtitleView().setFractionalTextSize(PlayerSettings.getSubtitleTextSize());
    }

    public static ExoPlayer buildPlayer(int decode, Player.Listener listener) {
        PlaybackHealthMonitor health = new PlaybackHealthMonitor();
        ExoPlayer player = new ExoPlayer.Builder(App.get()).setLoadControl(buildLoadControl(health)).setTrackSelector(buildTrackSelector()).setRenderersFactory(buildRenderersFactory(getRenderMode(decode))).setMediaSourceFactory(buildMediaSourceFactory(health)).build();
        if (BuildConfig.DEBUG) player.addAnalyticsListener(new EventLogger());
        player.setAudioAttributes(AudioAttributes.DEFAULT, true);
        player.setHandleAudioBecomingNoisy(true);
        player.setPlayWhenReady(true);
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC);
        // Match the display refresh rate to the content only when the panel can switch seamlessly
        // (e.g. 50Hz for 25/50fps, 24Hz for film). On a fixed 1080p@60 panel with a single mode this
        // is a no-op, but it removes judder on TVs/boxes that report multiple refresh rates. The
        // default strategy is OFF, which never switches.
        player.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS);
        player.addListener(listener);
        return player;
    }

    public static MediaItem getMediaItem(PlaySpec spec) {
        MediaItem.Builder builder = new MediaItem.Builder().setUri(spec.getUri());
        builder.setSubtitleConfigurations(buildSubtitleConfigs(spec.getSubs()));
        builder.setDrmConfiguration(buildDrmConfig(spec.getDrm()));
        builder.setRequestMetadata(buildRequestMetadata(spec));
        builder.setMediaMetadata(spec.getMetadata());
        builder.setMimeType(spec.getFormat());
        builder.setImageDurationMs(15000);
        builder.setMediaId(spec.getKey());
        return builder.build();
    }

    /**
     * Extracts a format hint from the playback error so the engine can retry
     * with an explicit MIME type when the source URL lacks a recognised
     * extension or Content-Type.
     *
     * <p>A provider that already supplies a format in its JSON (Baidu dlink,
     * Quark share/streaming, etc.) will not benefit from a hint, so the
     * caller must skip the retry when the existing format matches the hint.
     */
    public static String getMimeType(PlaybackException error) {
        if (error == null) return null;
        int errorCode = error.errorCode;
        if (errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
                || errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED) {
            String message = error.getMessage();
            if (message != null) {
                if (message.contains("mimeType=application/x-mpegURL")
                        || message.contains("mimeType=application/vnd.apple.mpegurl")
                        || message.contains("MimeTypes.application/x-mpegURL")) {
                    return null;
                }
            }
            return MimeTypes.APPLICATION_M3U8;
        }
        return null;
    }

    public static Map<String, String> extractHeaders(MediaItem item) {
        Bundle extras = item.requestMetadata.extras;
        if (extras == null) return Collections.emptyMap();
        Map<String, String> headers = new HashMap<>();
        for (String key : extras.keySet()) {
            String value = extras.getString(key);
            if (value != null) headers.put(key, value);
        }
        return headers;
    }

    private static int getRenderMode(int decode) {
        return decode == PlayerEngine.HARD ? DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON : DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER;
    }

    /**
     * Minimal, framework-free description of a decoder's tunneled-playback support. It exists so the
     * gate logic can be unit-tested on the JVM without a device (see ExoUtilTest). The Android scan in
     * {@link #supportsTunnelingVideo()} maps real MediaCodecInfo into these.
     */
    static final class CodecTunnelSupport {
        final boolean encoder;
        final List<String> supportedTypes;
        final boolean tunneledPlayback;

        CodecTunnelSupport(boolean encoder, List<String> supportedTypes, boolean tunneledPlayback) {
            this.encoder = encoder;
            this.supportedTypes = supportedTypes;
            this.tunneledPlayback = tunneledPlayback;
        }
    }

    /**
     * Pure decision: true when any non-encoder codec reports tunneled-playback support for a video
     * type. Framework-free and unit-testable; {@link #supportsTunnelingVideo()} feeds it the device's
     * real codecs.
     */
    static boolean anyVideoDecoderSupportsTunneling(List<CodecTunnelSupport> codecs) {
        for (CodecTunnelSupport codec : codecs) {
            if (codec.encoder) continue;
            for (String type : codec.supportedTypes) {
                if (type.startsWith("video/") && codec.tunneledPlayback) return true;
            }
        }
        return false;
    }

    /**
     * Returns true when at least one video decoder on the device advertises tunneled-playback
     * support. Tunneling is a hardware/codec feature; when it is absent (e.g. Allwinner OMX
     * decoders), Media3 cannot use it regardless of the user setting. Querying once per player
     * build is cheap and lets us skip the failed tunnel negotiation on unsupported devices.
     */
    static boolean supportsTunnelingVideo() {
        com.wuming.tv.core.playback.HardwareCapabilityReport saved = VideoCapabilityCache.get().savedReport();
        if (saved != null) return saved.getDecoders().stream().anyMatch(codec -> codec.getTunneling());
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false;
        List<CodecTunnelSupport> codecs = new ArrayList<>();
        MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
        for (MediaCodecInfo info : list.getCodecInfos()) {
            if (info.isEncoder()) continue;
            List<String> types = new ArrayList<>(Arrays.asList(info.getSupportedTypes()));
            boolean tunnel = false;
            for (String type : types) {
                if (!type.startsWith("video/")) continue;
                try {
                    MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
                    if (caps != null && caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback)) {
                        tunnel = true;
                        break;
                    }
                } catch (Exception ignored) {
                    // Some decoders throw on unsupported types; skip them.
                }
            }
            codecs.add(new CodecTunnelSupport(false, types, tunnel));
        }
        return anyVideoDecoderSupportsTunneling(codecs);
    }

    private static CaptionStyleCompat getCaptionStyle() {
        return PlayerSettings.isCaption() ? CaptionStyleCompat.createFromCaptionStyle(((CaptioningManager) App.get().getSystemService(Context.CAPTIONING_SERVICE)).getUserStyle()) : new CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null);
    }

    private static LoadControl buildLoadControl(PlaybackHealthMonitor health) {
        return new SmoothnessLoadControl(PlayerSettings.getBuffer(), health);
    }

    private static TrackSelector buildTrackSelector() {
        DefaultTrackSelector trackSelector = new CapabilityTrackSelector(App.get());
        DefaultTrackSelector.Parameters.Builder builder = trackSelector.buildUponParameters();
        // Regression guard (2026-09-10): false blocked Quark E01 4K/60 HEVC before decoding.
        // On phone AUTHORIZED_PHONE_SERIAL, support=FORMAT_EXCEEDS_CAPABILITIES still produced actual
        // 3840x2160 hardware-decoded frames. Keep the attempt enabled in release too; reported
        // performance limits are not proof of decoder failure (nor a promise of smooth 60 fps).
        // Prefer supported tracks; see docs/quark-smart-quality-lessons.md before tightening this.
        builder.setExceedRendererCapabilitiesIfNecessary(true);
        if (PlayerSettings.isPreferAAC()) builder.setPreferredAudioMimeType(MimeTypes.AUDIO_AAC);
        builder.setPreferredTextLanguage(Locale.getDefault().getISO3Language());
        // Only request tunneled playback when the user opted in AND a video decoder on this
        // device actually advertises FEATURE_TunneledPlayback. The Allwinner OMX decoder rejects
        // the tunnel extension (GetExtensionIndex configureVideoTunnelMode -> err 0x80001001), so
        // forcing it there only spams failed queries and yields no benefit.
        builder.setTunnelingEnabled(PlayerSettings.isTunnel() && supportsTunnelingVideo());
        // Let Media3 adapt HLS/DASH/SS quality to measured bandwidth. This has no effect on a
        // single progressive file, while avoiding startup stalls caused by always selecting the
        // highest adaptive rendition before a bandwidth estimate exists.
        builder.setForceHighestSupportedBitrate(false);
        trackSelector.setParameters(builder.build());
        return trackSelector;
    }

    private static RenderersFactory buildRenderersFactory(int renderMode) {
        return new CapabilityRenderersFactory(App.get()).setEnableDecoderFallback(true).setExtensionRendererMode(renderMode);
    }

    private static MediaSource.Factory buildMediaSourceFactory(PlaybackHealthMonitor health) {
        return new MediaSourceFactory(health).setLoadErrorHandlingPolicy(buildLoadErrorHandlingPolicy());
    }

    static LoadErrorHandlingPolicy buildLoadErrorHandlingPolicy() {
        return new DefaultLoadErrorHandlingPolicy() {
            @Override
            public long getRetryDelayMsFor(LoadErrorHandlingPolicy.LoadErrorInfo loadErrorInfo) {
                // Preserve Media3's fatal-error classification. Retrying the same unsupported
                // container delays the engine's explicit-format fallback without changing bytes.
                if (super.getRetryDelayMsFor(loadErrorInfo) == C.TIME_UNSET) return C.TIME_UNSET;
                if (UnsupportedVideoException.isCapabilityRejection(loadErrorInfo.exception)) return C.TIME_UNSET;
                Integer status = HttpPlaybackErrors.responseCode(loadErrorInfo.exception);
                if (status != null) {
                    if (loadErrorInfo.errorCount > 3) return C.TIME_UNSET;
                    Long delay = com.wuming.tv.core.playback.PlaybackHttpRetryPolicy.retryDelayMillis(
                            status, loadErrorInfo.errorCount,
                            HttpPlaybackErrors.retryAfter(loadErrorInfo.exception), System.currentTimeMillis());
                    return delay == null ? C.TIME_UNSET : delay;
                }
                Long delay = com.wuming.tv.core.playback.PlaybackLoadRetryPolicy.retryDelayMillis(
                        loadErrorInfo.exception, loadErrorInfo.errorCount);
                return delay == null ? C.TIME_UNSET : delay;
            }

            @Override
            public int getMinimumLoadableRetryCount(int dataType) {
                return dataType == C.DATA_TYPE_MEDIA ? 3 : 2;
            }
        };
    }

    private static MediaItem.RequestMetadata buildRequestMetadata(PlaySpec spec) {
        return new MediaItem.RequestMetadata.Builder().setMediaUri(spec.getUri()).setExtras(PlayerHelper.toBundle(spec.getHeaders())).build();
    }

    private static List<MediaItem.SubtitleConfiguration> buildSubtitleConfigs(List<Sub> subs) {
        if (subs == null || subs.isEmpty()) return Collections.emptyList();
        List<MediaItem.SubtitleConfiguration> configs = new ArrayList<>(subs.size());
        for (Sub sub : subs) configs.add(buildSubConfig(sub));
        return configs;
    }

    private static MediaItem.SubtitleConfiguration buildSubConfig(Sub sub) {
        return new MediaItem.SubtitleConfiguration.Builder(Uri.parse(UrlUtil.convert(sub.getUrl()))).setLabel(sub.getName()).setMimeType(sub.getFormat()).setSelectionFlags(sub.getFlag()).setLanguage(sub.getLang()).build();
    }

    private static MediaItem.DrmConfiguration buildDrmConfig(Drm drm) {
        return drm == null ? null : new MediaItem.DrmConfiguration.Builder(drm.getUUID()).setMultiSession(!C.CLEARKEY_UUID.equals(drm.getUUID())).setForceDefaultLicenseUri(drm.isForceKey()).setLicenseRequestHeaders(drm.getHeader()).setLicenseUri(drm.getKey()).build();
    }
}
