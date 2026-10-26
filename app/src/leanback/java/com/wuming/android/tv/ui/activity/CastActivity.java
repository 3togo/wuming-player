package com.wuming.android.tv.ui.activity;
import com.wuming.android.tv.setting.DisplaySettings;
import com.wuming.android.tv.setting.PlayerSettings;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.KeyEvent;
import android.view.View;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.ui.PlayerView;
import androidx.viewbinding.ViewBinding;

import com.wuming.android.tv.App;
import com.wuming.android.tv.Constant;
import com.wuming.android.tv.R;
import com.wuming.android.tv.bean.Sub;
import com.wuming.android.tv.bean.Track;
import com.wuming.android.tv.databinding.ActivityCastBinding;
import com.wuming.android.tv.dlna.CastAction;
import com.wuming.android.tv.event.RefreshEvent;
import com.wuming.android.tv.player.PlayerHelper;
import com.wuming.android.tv.player.PlayerManager;
import com.wuming.android.tv.service.DLNARendererService;
import com.wuming.android.tv.service.PlaybackService;
import com.wuming.android.tv.ui.base.PlaybackActivity;
import com.wuming.android.tv.ui.custom.CustomKeyDownVod;
import com.wuming.android.tv.ui.custom.CustomSeekView;
import com.wuming.android.tv.ui.dialog.SubtitleDialog;
import com.wuming.android.tv.ui.dialog.TrackDialog;
import com.wuming.android.tv.utils.BundleUtil;
import com.wuming.android.tv.utils.Clock;
import com.wuming.android.tv.utils.TvRemoteKeys;
import com.wuming.android.tv.utils.TvRemoteMappings;
import com.wuming.android.tv.utils.ResUtil;
import com.wuming.android.tv.utils.Traffic;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.fourthline.cling.support.contentdirectory.DIDLParser;

public class CastActivity extends PlaybackActivity implements CustomKeyDownVod.Listener, TrackDialog.Listener {

    private ActivityCastBinding mBinding;
    private DLNARendererService mRenderer;
    private CustomKeyDownVod mKeyDown;
    private String mPlaybackKey;
    private CastAction mAction;
    private Runnable mR1;
    private Runnable mR2;
    private Clock mClock;
    private long position;
    private int scale;
    private boolean mAudioTrackApplied;
    private boolean mRendererBound;

    @Override
    protected boolean customWall() {
        return false;
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityCastBinding.inflate(getLayoutInflater());
    }

    @Override
    protected PlaybackService.NavigationCallback getNavigationCallback() {
        return mNavigationCallback;
    }

    @Override
    protected String getPlaybackKey() {
        return mPlaybackKey;
    }

    @Override
    protected PlayerView getExoView() {
        return mBinding.exo;
    }

    @Override
    protected CustomSeekView getSeekView() {
        return mBinding.control.seek;
    }

    @Override
    protected void onServiceConnected() {
        PlayerManager manager = player();
        if (manager == null) return;
        mBinding.control.action.speed.setText(manager.getSpeedText());
        mBinding.control.action.decode.setText(manager.getDecodeText());
        // Replay the latest intent deferred by setAction while playback was binding.
        setAction(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Store before setAction: an early return there must not lose a new cast request.
        setIntent(intent);
        if (mRenderer != null) mRenderer.setDlnaActive(true);
        if (intent.hasExtra(CastAction.KEY_EXTRA)) setAction(intent);
        else finish();
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        super.initView(savedInstanceState);
        mRendererBound = bindService(new Intent(this, DLNARendererService.class), mRendererConnection, Context.BIND_AUTO_CREATE);
        mClock = Clock.create(mBinding.widget.clock);
        mKeyDown = CustomKeyDownVod.create(this);
        mKeyDown.setFull(true);
        mR1 = this::hideControl;
        mR2 = this::setTraffic;
        setVideoView();
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    protected void initEvent() {
        mBinding.control.action.speed.setUpListener(this::onSpeedAdd);
        mBinding.control.action.speed.setDownListener(this::onSpeedSub);
        mBinding.control.action.subtitleTracks.setUpListener(this::onSubtitleClick);
        mBinding.control.action.subtitleTracks.setDownListener(this::onSubtitleClick);
        mBinding.control.action.subtitleTracks.setOnClickListener(this::onTrack);
        mBinding.control.action.audio.setOnClickListener(this::onTrack);
        mBinding.control.action.videoTracks.setOnClickListener(this::onTrack);
        mBinding.control.action.scale.setOnClickListener(view -> onScale());
        mBinding.control.action.speed.setOnClickListener(view -> onSpeed());
        mBinding.control.action.reset.setOnClickListener(view -> onReset());
        mBinding.control.action.player.setOnClickListener(view -> onChoose());
        mBinding.control.action.decode.setOnClickListener(view -> onDecode());
        mBinding.control.action.speed.setOnLongClickListener(view -> onSpeedLong());
        mBinding.video.setOnTouchListener((view, event) -> mKeyDown.onTouchEvent(event));
    }

    private void setVideoView() {
        setScale(scale = DisplaySettings.getScale());
        findViewById(R.id.timeBar).setNextFocusUpId(R.id.reset);
        mBinding.control.action.reset.setText(ResUtil.getStringArray(R.array.select_reset)[0]);
    }

    private String getName() {
        try {
            return new DIDLParser().parse(mAction.getCurrentURIMetaData()).getItems().get(0).getTitle();
        } catch (Exception e) {
            return mAction.getCurrentURI();
        }
    }

    private void setAction(Intent intent) {
        // singleTop can deliver another cast before the asynchronous service bind completes.
        // Keep this guard before resetMedia/start: resetMedia calls player().setSpeed(),
        // which crashed when the player was still null. onNewIntent stores the latest
        // intent for onServiceConnected to replay; do not drop that deferred replay.
        // Regression coverage: CastActivityStartupInstrumentedTest.
        if (service() == null || player() == null) return;
        mAction = BundleUtil.getParcelableExtra(intent, CastAction.KEY_EXTRA, CastAction.class);
        if (mAction == null) return;
        mAudioTrackApplied = false;
        mBinding.widget.overlayTitle.setText(getName());
        mBinding.widget.overlayTitle.setSelected(true);
        resetMedia();
        start();
    }

    private void resetMedia() {
        hideError();
        hideControl();
        player().setSpeed(1.0f);
        player().setRepeatOne(false);
    }

    private void start() {
        mPlaybackKey = mAction.getCurrentURI();
        startPlayer(mPlaybackKey, mAction.result(), false, Constant.TIMEOUT_PLAY, buildMetadata());
    }

    private void setDecode() {
        mBinding.control.action.decode.setText(player().getDecodeText());
    }

    private void setScale(int scale) {
        mBinding.exo.setResizeMode(scale);
        mBinding.control.action.scale.setText(ResUtil.getStringArray(R.array.select_scale)[scale]);
    }

    private void onScale() {
        String[] array = ResUtil.getStringArray(R.array.select_scale);
        scale = scale == array.length - 1 ? 0 : ++scale;
        setScale(scale);
    }

    private void onSpeed() {
        mBinding.control.action.speed.setText(player().addSpeed());
    }

    private void onSpeedAdd() {
        mBinding.control.action.speed.setText(player().addSpeed(0.25f));
    }

    private void onSpeedSub() {
        mBinding.control.action.speed.setText(player().subSpeed(0.25f));
    }

    private boolean onSpeedLong() {
        mBinding.control.action.speed.setText(player().toggleSpeed());
        return true;
    }

    private void onReset() {
        if (player().isEmpty()) return;
        position = player().getPosition();
        start();
    }

    private void onChoose() {
        PlayerHelper.choose(this, player().getUrl(), player().getHeaders(), player().isVod(), player().getPosition(), mBinding.widget.overlayTitle.getText());
        setRedirect(true);
    }

    private void onDecode() {
        if (player().isEmpty()) return;
        position = player().getPosition();
        player().toggleDecode();
        setDecode();
    }

    private void onTrack(View view) {
        TrackDialog.create().type(Integer.parseInt(view.getTag().toString())).player(player()).show(this);
        hideControl();
    }

    private void onToggle() {
        if (isVisible(mBinding.control.getRoot())) hideControl();
        else showControl();
    }

    private void showProgress() {
        mBinding.progress.getRoot().setVisibility(View.VISIBLE);
        App.post(mR2, 0);
        hideCenter();
        hideError();
    }

    private void hideProgress() {
        mBinding.progress.getRoot().setVisibility(View.GONE);
        App.removeCallbacks(mR2);
        Traffic.reset();
    }

    private void showError(String text) {
        mBinding.widget.error.setVisibility(View.VISIBLE);
        mBinding.widget.overlayText.setText(text);
        hideProgress();
    }

    private void hideError() {
        mBinding.widget.error.setVisibility(View.GONE);
        mBinding.widget.overlayText.setText("");
    }

    private void showInfo() {
        mBinding.widget.top.setVisibility(View.VISIBLE);
        mBinding.widget.center.setVisibility(View.VISIBLE);
        mBinding.widget.duration.setText(player().getDurationTime());
        mBinding.widget.position.setText(player().getPositionTime(0));
    }

    private void hideInfo() {
        mBinding.widget.top.setVisibility(View.GONE);
        mBinding.widget.center.setVisibility(View.GONE);
    }

    private void showControl() {
        mBinding.control.getRoot().setVisibility(View.VISIBLE);
        mBinding.control.action.reset.requestFocus();
        setR1Callback();
    }

    private void hideControl() {
        mBinding.control.getRoot().setVisibility(View.GONE);
        App.removeCallbacks(mR1);
    }

    private void hideCenter() {
        mBinding.widget.overlayAction.setImageResource(R.drawable.ic_widget_play);
        hideInfo();
    }

    private void setTraffic() {
        Traffic.setSpeed(mBinding.progress.traffic);
        App.post(mR2, 1000);
    }

    private void setR1Callback() {
        App.post(mR1, Constant.INTERVAL_HIDE);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (event.getType() == RefreshEvent.Type.PLAYER) onReset();
        else if (event.getType() == RefreshEvent.Type.SUBTITLE) player().setSub(Sub.from(event.getPath()));
    }

    private void setTrackVisible() {
        mBinding.control.action.subtitleTracks.setVisibility(player().haveTrack(C.TRACK_TYPE_TEXT) || player().isVod() ? View.VISIBLE : View.GONE);
        mBinding.control.action.audio.setVisibility(player().haveTrack(C.TRACK_TYPE_AUDIO) ? View.VISIBLE : View.GONE);
        mBinding.control.action.videoTracks.setVisibility(player().haveTrack(C.TRACK_TYPE_VIDEO) ? View.VISIBLE : View.GONE);
    }

    private MediaMetadata buildMetadata() {
        return PlayerManager.buildMetadata(mBinding.widget.overlayTitle.getText().toString(), "", "");
    }

    private void onPaused() {
        controller().pause();
    }

    private void onPlay() {
        if (isEnded()) controller().seekTo(0);
        if (!player().isEmpty() && isIdle()) controller().prepare();
        controller().play();
    }

    private void consumePendingSeek() {
        if (service() == null || player().isEmpty() || mRenderer == null) return;
        long seekMs = mRenderer.consumePendingSeekMs();
        if (seekMs >= 0) player().seekTo(seekMs);
    }

    private final ServiceConnection mRendererConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (isFinishing() || isDestroyed()) return;
            mRenderer = ((DLNARendererService.LocalBinder) binder).getService();
            if (mRenderer == null) return;
            mRenderer.setDlnaActive(true);
            consumePendingSeek();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mRenderer = null;
        }
    };

    private final PlaybackService.NavigationCallback mNavigationCallback = new PlaybackService.NavigationCallback() {
        @Override
        public void onStop() {
            finish();
        }
    };

    @Override
    protected void onPrepare() {
        setDecode();
        setPosition();
        consumePendingSeek();
    }

    @Override
    protected void onTracksChanged() {
        setTrackVisible();
        applyPreferredAudioTrack();
    }

    private void applyPreferredAudioTrack() {
        if (mAudioTrackApplied) return;
        if (mAction == null || mAction.getAudioTrackFormat() == null) return;
        if (player() == null || player().isEmpty()) return;
        mAudioTrackApplied = true;
        String preferredFormat = mAction.getAudioTrackFormat();
        Tracks tracks = player().getCurrentTracks();
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_AUDIO) continue;
            for (int i = 0; i < group.length; i++) {
                Format format = group.getTrackFormat(i);
                if (preferredFormat.equals(PlayerHelper.describeFormat(format))) {
                    Track track = new Track(C.TRACK_TYPE_AUDIO, format.label != null ? format.label : "", preferredFormat);
                    track.setSelected(true);
                    track.key(player().getKey());
                    player().setTrack(java.util.Arrays.asList(track));
                    return;
                }
            }
        }
    }

    @Override
    protected void onError(String msg) {
        if (mRenderer != null) mRenderer.notifyError();
        player().resetTrack();
        player().reset();
        player().stop();
        showError(msg);
    }

    @Override
    protected void onStateChanged(int state) {
        switch (state) {
            case Player.STATE_BUFFERING:
                showProgress();
                break;
            case Player.STATE_READY:
                hideProgress();
                player().reset();
                break;
            case Player.STATE_ENDED:
                checkEnded();
                break;
        }
    }

    private void setPosition() {
        if (position <= 0) return;
        player().seekTo(position);
        position = 0;
    }

    private void checkEnded() {
        CastAction next = mRenderer != null ? mRenderer.consumeNext() : null;
        if (next == null) return;
        mAction = next;
        mAudioTrackApplied = false;
        mBinding.widget.overlayTitle.setText(getName());
        mBinding.widget.overlayTitle.setSelected(true);
        resetMedia();
        start();
    }

    @Override
    protected void onPlayingChanged(boolean isPlaying) {
        if (isPlaying) {
            hideCenter();
        } else if (isPaused()) {
            showInfo();
        }
    }

    @Override
    public void onSubtitleClick() {
        SubtitleDialog.create().view(mBinding.exo.getSubtitleView()).full(true).show(this);
        postUi(this::hideControl, 100);
    }

    private TvRemoteMappings remoteMappings;

    private void onRemoteCommand(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_MENU: onToggle(); break;
            case KeyEvent.KEYCODE_CAPTIONS: onSubtitleClick(); break;
            case KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK: onTrack(mBinding.control.action.audio); break;
            case KeyEvent.KEYCODE_INFO: showInfo(); break;
            case KeyEvent.KEYCODE_MEDIA_PLAY: onPlay(); break;
            case KeyEvent.KEYCODE_MEDIA_PAUSE: onPaused(); break;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (player().isPlaying()) onPaused(); else onPlay();
                break;
            case KeyEvent.KEYCODE_MEDIA_REWIND: controller().seekBack(); break;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: controller().seekForward(); break;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (remoteMappings == null) remoteMappings = new TvRemoteMappings(this);
        event = remoteMappings.translate(event);
        if (service() != null && TvRemoteKeys.dispatch(event, this::onRemoteCommand)) return true;
        if (isVisible(mBinding.control.getRoot())) setR1Callback();
        if (isGone(mBinding.control.getRoot()) && mKeyDown.hasEvent(event) && service() != null) return mKeyDown.onKeyDown(event);
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onSeeking(long time) {
        if (player().isEmpty()) return;
        mBinding.widget.center.setVisibility(View.VISIBLE);
        mBinding.widget.duration.setText(player().getDurationTime());
        mBinding.widget.position.setText(player().getPositionTime(time));
        mBinding.widget.overlayAction.setImageResource(time > 0 ? R.drawable.ic_widget_forward : R.drawable.ic_widget_rewind);
        hideProgress();
    }

    @Override
    public void onSeekEnd(long time) {
        if (player().isEmpty()) return;
        mKeyDown.reset();
        seekTo(time);
    }

    @Override
    public void onSpeedUp() {
        if (!player().isPlaying()) return;
        mBinding.widget.overlaySpeed.setVisibility(View.VISIBLE);
        mBinding.widget.overlaySpeed.startAnimation(ResUtil.getAnim(R.anim.forward));
        mBinding.control.action.speed.setText(player().setSpeed(PlayerSettings.getSpeed()));
    }

    @Override
    public void onSpeedEnd() {
        mBinding.widget.overlaySpeed.clearAnimation();
        mBinding.widget.overlaySpeed.setVisibility(View.GONE);
        mBinding.control.action.speed.setText(player().setSpeed(1.0f));
    }

    @Override
    public void onKeyUp() {
        showControl();
    }

    @Override
    public void onKeyDown() {
        showControl();
    }

    @Override
    public void onKeyCenter() {
        if (player().isPlaying()) onPaused();
        else onPlay();
        hideControl();
    }

    @Override
    public void onSingleTap() {
        onToggle();
    }

    @Override
    public void onDoubleTap() {
        onKeyCenter();
    }

    @Override
    protected void onStart() {
        super.onStart();
        mClock.stop().start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (PlayerSettings.isBackgroundOff()) mClock.stop();
    }

    @Override
    protected void onBackInvoked() {
        if (isVisible(mBinding.control.getRoot())) {
            hideControl();
        } else if (isVisible(mBinding.widget.center)) {
            hideCenter();
        } else {
            super.onBackInvoked();
        }
    }

    private void releaseRenderer() {
        if (mRenderer != null) mRenderer.setDlnaActive(false);
        if (!mRendererBound) return;
        mRendererBound = false;
        try {
            unbindService(mRendererConnection);
        } catch (IllegalArgumentException ignored) {
            // Connection was already removed by the system.
        }
    }

    @Override
    protected void onDestroy() {
        if (mClock != null) mClock.release();
        releaseRenderer();
        App.removeCallbacks(mR1, mR2);
        // Keep the binding alive until super returns: PlaybackActivity.onDestroy()
        // calls releaseController() -> getSeekView(), which dereferences mBinding.
        // Clearing it first crashed when leaving the cast screen. The close path is
        // covered by CastActivityStartupInstrumentedTest's ActivityScenario cleanup.
        super.onDestroy();
        mBinding = null;
        mRenderer = null;
        if (mKeyDown != null) mKeyDown.release();
        mKeyDown = null;
        mR1 = null;
        mR2 = null;
    }
}
