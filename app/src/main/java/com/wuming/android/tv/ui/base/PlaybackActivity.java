package com.wuming.android.tv.ui.base;
import com.wuming.android.tv.setting.PlayerSettings;
import com.wuming.android.tv.setting.AppSettings;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.drm.FrameworkMediaDrm;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.media3.ui.PlayerView;

import com.wuming.android.tv.R;
import com.wuming.android.tv.bean.Result;
import com.wuming.tv.core.playback.HlsHttpProbeTransport;
import com.wuming.tv.core.playback.HlsSegmentProbeSession;
import com.wuming.android.tv.player.PlayerManager;
import com.wuming.android.tv.player.engine.PlaySpec;
import com.wuming.android.tv.player.exo.ExoUtil;
import com.wuming.android.tv.server.Server;
import com.wuming.android.tv.server.process.M3u8Proxy;
import com.wuming.android.tv.service.PlaybackService;
import com.wuming.android.tv.ui.custom.CustomSeekView;
import com.wuming.android.tv.utils.ResUtil;
import com.wuming.android.tv.utils.HlsPlaylistLocation;
import com.wuming.android.tv.utils.UrlUtil;
import com.wuming.android.tv.utils.Task;
import com.wuming.android.tv.utils.WeakRefRunnable;
import com.github.catvod.net.OkHttp;
import com.google.common.util.concurrent.ListenableFuture;

import java.lang.ref.WeakReference;
import java.util.concurrent.FutureTask;
import com.wuming.android.tv.utils.UiTaskScope;
import com.wuming.android.tv.utils.HttpResponseText;
import java.io.IOException;
import okhttp3.Call;
import okhttp3.Response;
import master.flame.danmaku.ui.widget.DanmakuView;

public abstract class PlaybackActivity extends BaseActivity implements MediaController.Listener, Player.Listener, ServiceConnection {

    private ListenableFuture<MediaController> mControllerFuture;
    private MediaController mController;
    private PlaybackService mService;
    private PlayerManager mPlayer;
    private boolean audioOnly;
    private boolean redirect;
    private boolean bound;
    private boolean stop;
    private boolean lock;
    private PlaylistJob playlistJob;
    private DanmakuView danmakuView;

    protected void bindDanmakuView(DanmakuView view) {
        danmakuView = view;
        PlayerManager manager = player();
        if (manager != null) manager.setDanmakuView(view);
    }

    protected MediaController controller() {
        return mController;
    }

    protected PlaybackService service() {
        return mService;
    }

    protected PlayerManager player() {
        if (mService != null) {
            mPlayer = mService.player();
        }
        if (mPlayer == null) {
            android.util.Log.w("PlaybackActivity", "player() called but no PlayerManager available");
        }
        return mPlayer;
    }

    protected boolean isRedirect() {
        return redirect;
    }

    protected void setRedirect(boolean redirect) {
        this.redirect = redirect;
        if (mService != null) mService.setNavigationCallback(redirect ? null : getNavigationCallback(), getPlaybackKey());
    }

    protected boolean isAudioOnly() {
        return audioOnly;
    }

    protected void setAudioOnly(boolean audioOnly) {
        this.audioOnly = audioOnly;
    }

    protected boolean isStop() {
        return stop;
    }

    protected void setStop(boolean stop) {
        this.stop = stop;
    }

    protected boolean isLock() {
        return lock;
    }

    protected void setLock(boolean lock) {
        this.lock = lock;
    }

    protected abstract PlaybackService.NavigationCallback getNavigationCallback();

    protected abstract CustomSeekView getSeekView();

    protected abstract PlayerView getExoView();

    protected abstract String getPlaybackKey();

    protected boolean isOwner() {
        String key = getPlaybackKey();
        if (key == null) return true;
        PlayerManager player = player();
        return player != null && key.equals(player.getKey());
    }

    protected boolean isIdle() {
        return mController != null && mController.getPlaybackState() == Player.STATE_IDLE;
    }

    protected boolean isEnded() {
        return mController != null && mController.getPlaybackState() == Player.STATE_ENDED;
    }

    protected boolean isBuffering() {
        return mController != null && mController.getPlaybackState() == Player.STATE_BUFFERING;
    }

    protected boolean isPaused() {
        return mController != null && !mController.isPlaying() && !isBuffering() && !isIdle();
    }

    protected void onServiceConnected() {
    }

    protected void onPrepare() {
    }

    protected void onTracksChanged() {
    }

    protected void onTitlesChanged() {
    }

    protected void onError(String msg) {
    }

    protected void onPlayingChanged(boolean isPlaying) {
    }

    protected void onStateChanged(int state) {
    }

    protected void onSizeChanged(VideoSize size) {
    }

    protected void onReclaim() {
    }

    protected void seekTo(long time) {
        PlayerManager player = player();
        MediaController controller = mController;
        if (player == null || controller == null) return;
        controller.seekTo(player.getPosition() + time);
        controller.play();
    }

    protected boolean allowsDesktopAssistance() { return false; }

    protected void startPlayer(String key, Result result, boolean useParse, long timeout, MediaMetadata metadata) {
        cancelPlaylist();
        PlayerManager player = player();
        if (player == null) {
            onError("Player not available");
            return;
        }
        if (result.getDrm() != null && !FrameworkMediaDrm.isCryptoSchemeSupported(result.getDrm().getUUID())) {
            onError(ResUtil.getString(R.string.error_play_drm));
        } else if (result.hasMsg()) {
            onError(result.getMsg());
        } else if (result.needParse() || useParse) {
            attachSurface();
            player.parse(key, result, useParse, metadata);
        } else {
            attachSurface();
            if (AppSettings.isPurify() && !useParse) {
                purifyM3u8(key, result, timeout, metadata);
            } else {
                player.start(PlaySpec.from(result, key, metadata).withDesktopAssistance(allowsDesktopAssistance()), timeout);
            }
        }
    }

    private void purifyM3u8(String key, Result result, long timeout, MediaMetadata metadata) {
        PlayerManager player = player();
        if (player == null) {
            onError("Player not available");
            return;
        }
        String url = result.getRealUrl();
        if (!HlsPlaylistLocation.shouldPurify(url)) {
            player.start(PlaySpec.from(result, key, metadata).withDesktopAssistance(allowsDesktopAssistance()), timeout);
            return;
        }
        playlistJob = new PlaylistJob(this, key, result, timeout, metadata);
        Task.executor().execute(playlistJob.future);
    }

    private void cancelPlaylist() {
        if (playlistJob != null) playlistJob.close();
        playlistJob = null;
    }

    private static final class PlaylistJob implements Runnable {
        private final WeakReference<PlaybackActivity> owner;
        private final String key;
        private final Result result;
        private final long timeout;
        private final MediaMetadata metadata;
        private volatile boolean closed;
        private Call call;
        private final HlsHttpProbeTransport probeTransport;
        private final HlsSegmentProbeSession probeSession;
        private final FutureTask<Void> future;
        private final UiTaskScope delivery = new UiTaskScope();

        PlaylistJob(PlaybackActivity owner, String key, Result result, long timeout, MediaMetadata metadata) {
            this.owner = new WeakReference<>(owner);
            this.key = key;
            this.result = result;
            this.timeout = timeout;
            this.metadata = metadata;
            this.probeTransport = new HlsHttpProbeTransport(OkHttp.newClientBuilder().build());
            this.probeSession = new HlsSegmentProbeSession(result.getHeader(), probeTransport, () -> closed);
            this.future = new FutureTask<>(this, null);
        }

        synchronized void close() {
            closed = true;
            owner.clear();
            if (call != null) call.cancel();
            probeTransport.close();
            future.cancel(true);
            delivery.close();
            call = null;
        }

        private String read(String url) throws IOException {
            Call current = OkHttp.newCall(url, result.getHeader());
            synchronized (this) {
                if (closed) throw new IOException("Canceled");
                call = current;
            }
            try (Response response = current.execute()) {
                return HttpResponseText.read(response, 2 * 1024 * 1024);
            } finally {
                synchronized (this) { if (call == current) call = null; }
            }
        }

        private String prepare() throws IOException {
            String url = result.getRealUrl();
            String content = read(url);
            if (!content.startsWith("#EXTM3U")) return null;
            String lineSplit = content.contains("\r\n") ? "\r\n" : "\n";
            String forwardUrl = "";
            boolean dealtFirst = false;
            for (String line : content.split(lineSplit, 10)) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                if (dealtFirst) { forwardUrl = ""; break; }
                if (line.endsWith(".m3u8") || line.contains(".m3u8?")) {
                    forwardUrl = UrlUtil.resolve(url, line);
                }
                dealtFirst = true;
            }
            if (!forwardUrl.isEmpty()) {
                url = forwardUrl;
                content = read(forwardUrl);
            }
            // Fetch through the provider, but match its upstream host's rules. Relative
            // segments and keys still belong to the served playlist's transport URL.
            return com.wuming.android.tv.utils.M3u8.purify(HlsPlaylistLocation.baseUrl(url),
                    HlsPlaylistLocation.ruleBaseUrl(url, result.getRealUrl()), content, probeSession);
        }

        @Override
        public void run() {
            String prepared = null;
            try { prepared = prepare(); } catch (Exception error) {
                android.util.Log.w("HlsAdFilter", "Playlist preparation failed: " + error.getClass().getSimpleName());
            } finally {
                probeTransport.close();
            }
            android.util.Log.i("HlsAdFilter", "Playlist preparation: replaced=" + (prepared != null)
                    + ", segmentProbes=" + probeSession.getRequestCount());
            if (closed) return;
            String content = prepared;
            delivery.post(() -> {
                PlaybackActivity activity = owner.get();
                if (closed || activity == null || activity.isFinishing() || activity.isDestroyed() || activity.playlistJob != this) return;
                activity.playlistJob = null;
                owner.clear();
                Result playback = result;
                if (content != null) {
                    M3u8Proxy.setContent(content);
                    playback = Result.purify(result, Server.get().getAddress(true) + "/m3u8");
                }
                delivery.close();
                activity.startPlayerDirect(key, playback, timeout, metadata);
            }, 0);
        }
    }

    private void startPlayerDirect(String key, Result result, long timeout, MediaMetadata metadata) {
        PlayerManager player = player();
        if (player != null) player.start(PlaySpec.from(result, key, metadata).withDesktopAssistance(allowsDesktopAssistance()), timeout);
    }

    private void bindPlaybackService() {
        startService(new Intent(this, PlaybackService.class));
        bound = bindService(new Intent(this, PlaybackService.class).setAction(PlaybackService.LOCAL_BIND_ACTION), this, BIND_AUTO_CREATE);
        buildControllerAsync();
    }

    private void buildControllerAsync() {
        SessionToken token = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        mControllerFuture = new MediaController.Builder(this, token).setListener(this).buildAsync();
        mControllerFuture.addListener(this::onControllerConnected, ContextCompat.getMainExecutor(this));
    }

    private void onControllerConnected() {
        if (isDestroyed()) {
            releaseController();
            return;
        }
        try {
            mController = mControllerFuture.get();
            CustomSeekView seekView = getSeekView();
            if (seekView != null) seekView.setPlayer(mController);
            mController.addListener(this);
        } catch (Exception e) {
            android.util.Log.e("PlaybackActivity", "Failed to create MediaController", e);
        }
    }

    private PendingIntent buildSessionIntent() {
        Intent intent = new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        Bundle extras = getIntent().getExtras();
        if (extras != null) intent.putExtras(extras);
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private boolean shouldReclaim() {
        return mService != null && !isOwner();
    }

    private void closePiP() {
        if (!isInPictureInPictureMode()) return;
        detach();
        finish();
    }

    private void attachSurface() {
        if (mService != null && getExoView().getPlayer() == null) {
            PlayerManager player = player();
            if (player != null) getExoView().setPlayer(player.getPlayer());
        }
    }

    private void detachSurface() {
        getExoView().setPlayer(null);
    }

    private void setRender() {
        detachSurface();
        attachSurface();
    }

    private void releasePlaybackService() {
        if (mService != null) releaseService(isOwner());
        detach();
    }

    private void releaseService(boolean owner) {
        if (mService == null) return;
        mService.removePlayerCallback(mPlayerCallback);
        mService.replaceBinding(null);
        if (owner) mService.setNavigationCallback(null, null);
        if (mService.hasExternalClient() || mService.hasPlayerCallback()) {
            if (owner) mService.suspend();
            mService.resetSessionActivity();
        } else if (owner) {
            mService.shutdown();
        }
    }

    private void detach() {
        releaseController();
        releaseBinding();
    }

    private void releaseController() {
        if (mPlayer != null) mPlayer.clearDanmakuView(danmakuView);
        danmakuView = null;
        CustomSeekView seekView = getSeekView();
        if (seekView != null) seekView.setPlayer(null);
        if (mControllerFuture != null) MediaController.releaseFuture(mControllerFuture);
        if (mController != null) mController.removeListener(this);
        mControllerFuture = null;
        mController = null;
    }

    private void releaseBinding() {
        if (!bound) return;
        bound = false;
        unbindService(this);
        mService = null;
    }

    private static class PlayerCallbackImpl implements PlaybackService.PlayerCallback {
        private final WeakReference<PlaybackActivity> ref;

        PlayerCallbackImpl(PlaybackActivity activity) {
            this.ref = new WeakReference<>(activity);
        }

        @Override
        public void onPrepare() {
            PlaybackActivity activity = ref.get();
            if (activity != null && !activity.isDestroyed() && activity.isOwner()) activity.onPrepare();
        }

        @Override
        public void onTracksChanged() {
            PlaybackActivity activity = ref.get();
            if (activity != null && !activity.isDestroyed() && activity.isOwner()) activity.onTracksChanged();
        }

        @Override
        public void onTitlesChanged() {
            PlaybackActivity activity = ref.get();
            if (activity != null && !activity.isDestroyed() && activity.isOwner()) activity.onTitlesChanged();
        }

        @Override
        public void onError(String msg) {
            PlaybackActivity activity = ref.get();
            if (activity != null && !activity.isDestroyed() && activity.isOwner()) activity.onError(msg);
        }

        @Override
        public void onPlayerRebuild(Player player) {
            PlaybackActivity activity = ref.get();
            if (activity != null && !activity.isDestroyed() && activity.isOwner()) activity.setRender();
        }
    }

    private final PlaybackService.PlayerCallback mPlayerCallback = new PlayerCallbackImpl(this);

    @Override
    protected void initView(Bundle savedInstanceState) {
        super.initView(savedInstanceState);
        ExoUtil.setPlayerView(getExoView());
        bindPlaybackService();
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
        if (!isOwner() || isDestroyed()) return;
        if (isPlaying) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else if (!isBuffering()) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        onPlayingChanged(isPlaying);
    }

    @Override
    public void onPlaybackStateChanged(int state) {
        if (isOwner() && !isDestroyed()) onStateChanged(state);
    }

    @Override
    public void onVideoSizeChanged(@NonNull VideoSize size) {
        if (isOwner() && !isDestroyed()) onSizeChanged(size);
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        if (isFinishing() || isDestroyed()) return;
        mService = ((PlaybackService.LocalBinder) binder).getService();
        if (mService == null) return;
        mService.replaceBinding(new WeakRefRunnable<>(this, PlaybackActivity::closePiP));
        mService.setSessionActivity(buildSessionIntent());
        mService.setNavigationCallback(getNavigationCallback(), getPlaybackKey());
        mService.addPlayerCallback(mPlayerCallback);
        onServiceConnected();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        if (mService != null) mService.removePlayerCallback(mPlayerCallback);
        mService = null;
        releaseController();
        mPlayer = null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        setRedirect(false);
        if (shouldReclaim()) {
            detachSurface();
            onReclaim();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isRedirect() && mController != null) mController.pause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isOwner() && PlayerSettings.isBackgroundOff() && mController != null) mController.pause();
    }

    @Override
    protected void onDestroy() {
        cancelPlaylist();
        super.onDestroy();
        releasePlaybackService();
        mPlayer = null;
    }

    /**
     * A Runnable wrapper that holds a WeakReference to the Activity,
     * preventing the service from leaking the Activity through the stored callback.
     */
}
