package com.wuming.android.tv.player.engine;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;

import com.wuming.android.tv.R;
import com.wuming.android.tv.bean.Track;
import com.wuming.tv.core.playback.OpenDrivePlaybackHints;
import com.wuming.android.tv.player.exo.ErrorMsgProvider;
import com.wuming.android.tv.player.exo.ExoUtil;
import com.wuming.android.tv.player.exo.HttpPlaybackErrors;
import com.wuming.android.tv.player.exo.TrackUtil;
import com.wuming.android.tv.utils.AppLog;
import com.wuming.android.tv.utils.ResUtil;
import com.wuming.tv.core.playback.PlaybackHttpRetryPolicy;

import java.util.List;
import java.util.function.Function;

public class ExoPlayerEngine implements PlayerEngine {

    private final ErrorMsgProvider provider;
    private final Function<PlaySpec, MediaItem> mediaItemFactory;
    private PlaySpec spec;
    private Player player;
    private int decode;
    private boolean formatRetryAttempted;
    private long pendingRetryDelayMs;
    private long pendingRetryPositionMs;

    public ExoPlayerEngine(int decode, Player.Listener listener) {
        this(decode, ExoUtil.buildPlayer(decode, listener));
    }

    ExoPlayerEngine(int decode, Player player) {
        this(decode, player, ExoUtil::getMediaItem);
    }

    ExoPlayerEngine(int decode, Player player, Function<PlaySpec, MediaItem> mediaItemFactory) {
        this.player = player;
        this.provider = new ErrorMsgProvider();
        this.mediaItemFactory = mediaItemFactory;
        this.decode = decode;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    @Override
    public void release() {
        Player current = player;
        player = null;
        spec = null;
        pendingRetryDelayMs = 0L;
        if (current != null) current.release();
    }

    @Override
    public Player rebuild(Player.Listener listener) {
        if (player != null) player.removeListener(listener);
        release();
        return player = ExoUtil.buildPlayer(decode, listener);
    }

    @Override
    public int getDecode() {
        return decode;
    }

    @Override
    public void setDecode(int decode) {
        this.decode = decode;
    }

    @Override
    public boolean isHard() {
        return decode == HARD;
    }

    @Override
    public String getDecodeText() {
        return ResUtil.getStringArray(R.array.select_decode)[decode];
    }

    @Override
    public void start(PlaySpec spec) {
        if (player == null) return;
        this.spec = spec;
        this.formatRetryAttempted = false;
        this.networkRetryCount = 0;
        this.pendingRetryDelayMs = 0L;
        startInternal();
    }

    @Override
    public void setMetadata(MediaMetadata data) {
        if (player == null) return;
        MediaItem current = player.getCurrentMediaItem();
        if (current != null) player.replaceMediaItem(player.getCurrentMediaItemIndex(), current.buildUpon().setMediaMetadata(data).build());
    }

    @Override
    public boolean isLive() {
        return player != null && player.getCurrentMediaItem() != null && player.isCurrentMediaItemLive();
    }

    @Override
    public boolean isVod() {
        return player != null && player.getCurrentMediaItem() != null && !player.isCurrentMediaItemLive();
    }

    @Override
    public void setTrack(List<Track> tracks) {
        if (player != null) TrackUtil.setTrackSelection(player, tracks);
    }

    @Override
    public void resetTrack() {
        if (player != null) TrackUtil.reset(player);
    }

    @Override
    public boolean haveTrack(int type) {
        return TrackUtil.count(getCurrentTracks(), type) > 0;
    }

    @Override
    public Tracks getCurrentTracks() {
        return player == null ? Tracks.EMPTY : player.getCurrentTracks();
    }

    @Override
    public String getErrorMessage(PlaybackException e) {
        android.util.Log.e("ExoPlayerEngine", "PlaybackException: code=" + e.errorCode + ", name=" + e.getErrorCodeName(), e);
        return provider.get(e);
    }

    private int networkRetryCount;

    @Override
    public ErrorAction handleError(PlaybackException e) {
        if (player == null || spec == null) return ErrorAction.FATAL;
        // Preflight checked the available renderers/display. A decoder rebuild cannot
        // repair that rejection; let the screen try another source or show the reason.
        if (com.wuming.android.tv.player.exo.UnsupportedVideoException.isCapabilityRejection(e)) return ErrorAction.FATAL;
        return switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> seekToDefaultPosition();
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED -> ErrorAction.DECODE;
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> decodeFallback(e);
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> retryFormat(e);
            case PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> retryManifest();
            // The media loader already exhausted its connection retry budget. Restarting
            // the source here repeats that budget and hides the terminal error.
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> ErrorAction.FATAL;
            case PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> retryHttpStatus(e);
            default -> ErrorAction.FATAL;
        };
    }

    private void startInternal() {
        startInternal(0L);
    }

    private void startInternal(long resumePositionMs) {
        MediaItem item = mediaItemFactory.apply(spec);
        if (resumePositionMs > 0L) player.setMediaItem(item, resumePositionMs);
        else player.setMediaItem(item);
        player.prepare();
        player.play();
    }

    private ErrorAction seekToDefaultPosition() {
        player.seekToDefaultPosition();
        player.prepare();
        return ErrorAction.RECOVERED;
    }

    /**
     * The M3U8 format hint is only applied when the engine's best guess is a
     * mismatched progressive container. Baidu dlink URLs deliver MP4 files
     * whose Content-Type is often missing; forcing {@code application/x-mpegURL}
     * on them causes a {@code Manifest Malformed} failure. Only
     * share/streaming (genuine HLS) should carry the explicit format.
     */
    private ErrorAction retryFormat(PlaybackException error) {
        if (formatRetryAttempted) return ErrorAction.FATAL;
        formatRetryAttempted = true;
        String mimeType = ExoUtil.getMimeType(error);
        if (mimeType == null || mimeType.equals(spec.getFormat())) return ErrorAction.FATAL;
        spec.setFormat(mimeType);
        startInternal();
        return ErrorAction.RECOVERED;
    }

    /**
     * Manifest parse failures mean the response was not a valid playlist.
     * When a format hint is already set it may have been wrong (MP4 served
     * from a dlink CDN URL that was mistaken for HLS). Strip the format
     * hint and retry once so Media3 can auto-detect the real container.
     */
    private ErrorAction retryManifest() {
        if (formatRetryAttempted) return ErrorAction.FATAL;
        formatRetryAttempted = true;
        spec.setFormat(null);
        spec.setHeaders(OpenDrivePlaybackHints.without(
                spec.getHeaders(), OpenDrivePlaybackHints.HLS));
        startInternal();
        return ErrorAction.RECOVERED;
    }

    @Override
    public long pendingRetryDelayMs() {
        return pendingRetryDelayMs;
    }

    @Override
    public void retryAfterError() {
        if (player == null || spec == null || pendingRetryDelayMs <= 0L) return;
        long positionMs = pendingRetryPositionMs;
        pendingRetryDelayMs = 0L;
        startInternal(positionMs);
    }

    private ErrorAction retryNetwork(int statusCode, String retryAfter) {
        if (networkRetryCount >= 2) return ErrorAction.FATAL;
        Long delay = PlaybackHttpRetryPolicy.retryDelayMillis(
                statusCode, 4 + networkRetryCount, retryAfter, System.currentTimeMillis());
        if (delay == null) return ErrorAction.FATAL;
        networkRetryCount++;
        // A transient server error can happen after playback has progressed.
        // Preserve a seekable VOD position; live streams must rejoin at their live edge.
        pendingRetryPositionMs = !player.isCurrentMediaItemLive() && player.isCurrentMediaItemSeekable()
                ? Math.max(0L, player.getCurrentPosition()) : 0L;
        pendingRetryDelayMs = delay;
        return ErrorAction.RETRY;
    }

    private ErrorAction retryHttpStatus(PlaybackException e) {
        int statusCode = extractHttpStatusCode(e);
        // RefreshingDataSource already performs bounded renewal for refreshable cloud
        // locators. An error reaching the engine must be surfaced; the UI's default
        // onRefreshUrl callback is empty and previously left live playback silently idle.
        if (PlaybackHttpRetryPolicy.shouldRetry(statusCode)) {
            return retryNetwork(statusCode, HttpPlaybackErrors.retryAfter(e));
        }
        return ErrorAction.FATAL;
    }

    private ErrorAction decodeFallback(PlaybackException e) {
        if (decode == SOFT) return ErrorAction.FATAL;
        return ErrorAction.DECODE;
    }

    private static int extractHttpStatusCode(PlaybackException e) {
        Integer responseCode = HttpPlaybackErrors.responseCode(e);
        if (responseCode != null) return responseCode;
        String message = e.getMessage();
        if (message != null && message.contains("Response code:")) {
            try {
                int start = message.indexOf("Response code:") + 14;
                int end = message.indexOf(",", start);
                if (end == -1) end = message.indexOf("]", start);
                if (end == -1) end = message.length();
                return Integer.parseInt(message.substring(start, end).trim());
            } catch (Exception ex) {
                AppLog.w("ExoPlayer", "HTTP status code extraction failed: " + ex.getMessage());
            }
        }
        return 0;
    }

}
