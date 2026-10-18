package com.wuming.android.tv.ui.activity;
import com.wuming.android.tv.setting.DisplaySettings;
import com.wuming.android.tv.setting.PlayerSettings;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.C;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.bumptech.glide.request.transition.Transition;
import com.wuming.android.tv.App;
import com.wuming.android.tv.Constant;
import com.wuming.android.tv.R;
import com.wuming.android.tv.api.config.LiveConfig;
import com.wuming.android.tv.bean.CastVideo;
import com.wuming.android.tv.bean.Channel;
import com.wuming.android.tv.bean.Config;
import com.wuming.android.tv.bean.Epg;
import com.wuming.android.tv.bean.EpgData;
import com.wuming.android.tv.bean.Group;
import com.wuming.android.tv.bean.Keep;
import com.wuming.android.tv.bean.Live;
import com.wuming.android.tv.bean.Result;
import com.wuming.android.tv.bean.Track;
import com.wuming.android.tv.databinding.ActivityLiveBinding;
import com.wuming.android.tv.event.RefreshEvent;
import com.wuming.android.tv.impl.Callback;
import com.wuming.android.tv.impl.ConfigCallback;
import com.wuming.android.tv.impl.CustomTarget;
import com.wuming.android.tv.library.UserLibrary;
import com.wuming.android.tv.impl.LiveCallback;
import com.wuming.android.tv.impl.PassCallback;
import com.wuming.android.tv.model.LiveViewModel;
import com.wuming.android.tv.player.PlayerHelper;
import com.wuming.android.tv.player.PlayerManager;
import com.wuming.android.tv.player.Source;
import com.wuming.android.tv.service.PlaybackService;
import com.wuming.android.tv.ui.adapter.ChannelAdapter;
import com.wuming.android.tv.ui.adapter.EpgDataAdapter;
import com.wuming.android.tv.ui.adapter.MobileGroupAdapter;
import com.wuming.android.tv.ui.base.PlaybackActivity;
import com.wuming.android.tv.ui.custom.CustomKeyDown;
import com.wuming.android.tv.ui.custom.CustomSeekView;
import com.wuming.android.tv.api.projection.ProjectionManager;
import com.wuming.android.tv.server.CastMediaAccess;
import com.wuming.android.tv.ui.dialog.CastDialogV2;
import com.wuming.android.tv.ui.dialog.HistoryDialog;
import com.wuming.android.tv.ui.dialog.InfoDialog;
import com.wuming.android.tv.ui.dialog.LiveDialog;
import com.wuming.android.tv.ui.dialog.PassDialog;
import com.wuming.android.tv.ui.dialog.SubtitleDialog;
import com.wuming.android.tv.ui.dialog.TrackDialog;
import com.wuming.android.tv.ui.controller.LiveController;
import com.wuming.android.tv.ui.base.LivePlaybackResources;
import com.wuming.android.tv.ui.base.LiveSessionNavigator;
import com.wuming.android.tv.utils.Biometric;
import com.wuming.android.tv.utils.ImgUtil;
import com.wuming.android.tv.utils.Notify;
import com.wuming.android.tv.utils.PiP;
import com.wuming.android.tv.utils.ResUtil;
import com.wuming.android.tv.utils.Traffic;
import com.wuming.android.tv.utils.Util;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class MobileLiveActivity extends PlaybackActivity implements CustomKeyDown.Listener, TrackDialog.Listener, Biometric.Callback, PassCallback, ConfigCallback, LiveCallback, MobileGroupAdapter.OnClickListener, ChannelAdapter.OnClickListener, EpgDataAdapter.OnClickListener, InfoDialog.Listener {

    private ActivityLiveBinding mBinding;
    private LiveController mLiveController;
    private LivePlaybackResources mPlaybackResources;
    private ChannelAdapter mChannelAdapter;
    private EpgDataAdapter mEpgDataAdapter;
    private Observer<Result> mObserveUrl;
    private MobileGroupAdapter mGroupAdapter;
    private Observer<Epg> mObserveEpg;
    private LiveViewModel mViewModel;
    private CustomKeyDown mKeyDown;
    private List<Group> mHides;
    private String mPlaybackKey;
    private Channel mChannel;
    private Group mGroup;
    private Runnable mHideControlRunnable;
    private Runnable mSetTrafficRunnable;
    private Runnable mHideInfoRunnable;
    private boolean rotate;
    private int count;
    private PiP mPiP;

    public static void start(Context context) {
        context.startActivity(new Intent(context, MobileLiveActivity.class).putExtra("empty", LiveConfig.isEmpty()));
    }

    private boolean isEmpty() {
        return getIntent().getBooleanExtra("empty", true);
    }

    private Group getKeep() {
        return mGroupAdapter.get(0);
    }

    private Live getHome() {
        return LiveConfig.get().getHome();
    }

    @Override
    protected boolean customWall() {
        return false;
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityLiveBinding.inflate(getLayoutInflater());
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
        return mBinding == null ? null : mBinding.control.seek;
    }

    @Override
    protected void onServiceConnected() {
        mBinding.control.action.speed.setText(player().getSpeedText());
        mBinding.control.action.decode.setText(player().getDecodeText());
        checkLive();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Util.hideSystemUI(this);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        super.initView(savedInstanceState);
        ResUtil.scalePlayerOverlay(mBinding.videoInfo, mBinding.videoClock);
        mLiveController = new LiveController(this);
        mPlaybackResources = new LivePlaybackResources(this, null, mBinding.videoClock);
        mKeyDown = CustomKeyDown.create(this, mBinding.exo);
        setPadding(mBinding.control.getRoot());
        setPadding(mBinding.recycler, true);
        mObserveEpg = this::setEpg;
        mObserveUrl = this::start;
        mHides = new ArrayList<>();
        mHideControlRunnable = this::hideControl;
        mSetTrafficRunnable = this::setTraffic;
        mHideInfoRunnable = this::hideInfo;
        mPiP = new PiP();
        setRecyclerView();
        setVideoView();
        setViewModel();
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    protected void initEvent() {
        mBinding.control.back.setOnClickListener(view -> onBack());
        mBinding.control.cast.setOnClickListener(view -> onCast());
        mBinding.control.info.setOnClickListener(view -> onInfo());
        mBinding.control.play.setOnClickListener(view -> checkPlay());
        mBinding.control.next.setOnClickListener(view -> nextChannel());
        mBinding.control.prev.setOnClickListener(view -> prevChannel());
        mBinding.control.right.lock.setOnClickListener(view -> onLock());
        mBinding.control.right.rotate.setOnClickListener(view -> onRotate());
        mBinding.control.action.subtitleTracks.setOnClickListener(this::onTrack);
        mBinding.control.action.audio.setOnClickListener(this::onTrack);
        mBinding.control.action.videoTracks.setOnClickListener(this::onTrack);
        mBinding.control.action.home.setOnClickListener(view -> onHome());
        mBinding.control.action.line.setOnClickListener(view -> onLine());
        mBinding.control.action.scale.setOnClickListener(view -> onScale());
        mBinding.control.action.speed.setOnClickListener(view -> onSpeed());
        mBinding.control.action.config.setOnClickListener(view -> onConfig());
        mBinding.control.action.invert.setOnClickListener(view -> onInvert());
        mBinding.control.action.across.setOnClickListener(view -> onAcross());
        mBinding.control.action.change.setOnClickListener(view -> onChange());
        mBinding.control.action.player.setOnClickListener(view -> onChoose());
        mBinding.control.action.decode.setOnClickListener(view -> onDecode());
        mBinding.control.action.subtitleTracks.setOnLongClickListener(view -> onTextLong());
        mBinding.control.action.speed.setOnLongClickListener(view -> onSpeedLong());
        mBinding.control.action.getRoot().setOnTouchListener(this::onActionTouch);
        mBinding.video.setOnTouchListener((view, event) -> mKeyDown.onTouchEvent(event));
    }

    private void setRecyclerView() {
        mBinding.group.setItemAnimator(null);
        mBinding.channel.setItemAnimator(null);
        mBinding.epgData.setItemAnimator(null);
        mBinding.group.setAdapter(mGroupAdapter = new MobileGroupAdapter(this));
        mBinding.channel.setAdapter(mChannelAdapter = new ChannelAdapter(this));
        mBinding.epgData.setAdapter(mEpgDataAdapter = new EpgDataAdapter(this));
    }

    private void setVideoView() {
        setScale(DisplaySettings.getLiveScale());
        mBinding.control.action.invert.setActivated(DisplaySettings.isInvert());
        mBinding.control.action.across.setActivated(DisplaySettings.isAcross());
        mBinding.control.action.change.setActivated(DisplaySettings.isChange());
        mBinding.video.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> mPiP.update(this, view));
    }

    private void setDecode() {
        mBinding.control.action.decode.setText(player().getDecodeText());
    }

    private void setScale(int scale) {
        DisplaySettings.putLiveScale(scale);
        mBinding.exo.setResizeMode(scale);
        mBinding.control.action.scale.setText(ResUtil.getStringArray(R.array.select_scale)[scale]);
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(LiveViewModel.class);
        mViewModel.url().observe(this, mObserveUrl);
        mViewModel.xml().observe(this, this::setEpg);
        mViewModel.epg().observe(this, mObserveEpg);
        mViewModel.live().observe(this, live -> {
            mViewModel.parseXml(live);
            setGroup(live);
            setWidth(live);
        });
    }

    private void checkLive() {
        if (isEmpty()) {
            LiveConfig.get().init().load(getCallback());
        } else {
            getLive();
        }
    }

    private Callback getCallback() {
        return lifecycleCallback(new Callback() {
            @Override
            public void success() {
                getLive();
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
            }
        });
    }

    private void getLive() {
        mBinding.control.action.home.setText(mLiveController.getHomeButtonText());
        mViewModel.parse(getHome());
        showProgress();
    }

    private void setGroup(Live live) {
        LiveController.GroupFilterResult result = mLiveController.filterGroups(live);
        mHides = result.hiddenGroups;
        mGroupAdapter.addAll(result.visibleGroups);
        setPosition(LiveConfig.get().findKeepPosition(result.visibleGroups));
    }

    private void setWidth(Live live) {
        int width = mLiveController.calculateGroupWidth(live);
        setWidth(mBinding.group, width);
    }

    @Override
    public void setWidth(Group group) {
        int width = mLiveController.calculateChannelWidth(group);
        setWidth(mBinding.channel, width);
    }

    private void setWidth(Epg epg) {
        int width = mLiveController.calculateEpgWidth(epg);
        if (width == 0) return;
        setWidth(mBinding.epgData, width);
    }

    private void setWidth(View view, int width) {
        mLiveController.applyWidth(view, width);
    }

    private void setPosition(int[] position) {
        if (position[0] == -1) return;
        int size = mGroupAdapter.getItemCount();
        if (size == 1 || position[0] >= size) return;
        mGroup = mGroupAdapter.get(position[0]);
        mGroup.setPosition(position[1]);
        onItemClick(mGroup);
        Channel current = mGroup.current();
        if (current != null) onItemClick(current);
    }

    private void setPosition() {
        if (mChannel == null) return;
        mGroup = mChannel.getGroup();
        int position = mGroupAdapter.indexOf(mGroup);
        boolean change = mGroupAdapter.getPosition() != position;
        if (change) mGroupAdapter.setSelected(position);
        if (change) mChannelAdapter.addAll(mGroup.getChannel());
        if (change) mChannelAdapter.setSelected(mGroup.getPosition());
        scrollToPosition(mBinding.channel, mGroup.getPosition());
        scrollToPosition(mBinding.group, position);
    }

    private void onBack() {
        finish();
    }

    private void onCast() {
        // Choosing cast means local playback is no longer wanted, even if discovery or the
        // eventual handoff fails. Preserve the current position by pausing instead of stopping.
        player().pause();
        if (ProjectionManager.get().getConnectedDevice() != null) {
            ProjectionManager.get().disconnect();
            checkCastImg();
            Notify.show(R.string.device_disconnect);
        } else {
            String castUrl = CastMediaAccess.relayLive(player().getUrl(), player().getHeaders());
            CastDialogV2.create().video(new CastVideo(mBinding.control.title.getText().toString(), castUrl, androidx.media3.common.C.TIME_UNSET, java.util.Collections.emptyMap())).player(player()).fm(true).onCasted(() -> {
                player().pause();
                checkCastImg();
            }).show(this);
        }
    }

    private void onInfo() {
        InfoDialog.create(this).title(mBinding.control.title.getText()).headers(player().getHeaders()).url(player().getUrl()).show();
    }

    private void onLock() {
        setLock(!isLock());
        setRequestedOrientation(getLockOrient());
        mKeyDown.setLock(isLock());
        checkLockImg();
        showControl();
    }

    private void onRotate() {
        setR1Callback();
        setRotate(!isRotate());
        setRequestedOrientation(ResUtil.isLand(this) ? ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    private void checkPlay() {
        if (player().isPlaying()) onPaused();
        else onPlay();
    }

    private void onTrack(View view) {
        TrackDialog.create().type(Integer.parseInt(view.getTag().toString())).player(player()).show(this);
        hideControl();
    }

    private void onHome() {
        if (LiveConfig.isOnly()) setLive(getHome());
        else LiveDialog.create(this).show();
        hideControl();
    }

    private void onLine() {
        nextLine(false);
    }

    private void onScale() {
        int index = DisplaySettings.getLiveScale();
        String[] array = ResUtil.getStringArray(R.array.select_scale);
        if (mKeyDown.getScale() != 1.0f) mKeyDown.resetScale();
        else setScale(index == array.length - 1 ? 0 : ++index);
        setR1Callback();
    }

    private void onSpeed() {
        mPlaybackResources.playerHandler().setPlayer(player());
        mBinding.control.action.speed.setText(mPlaybackResources.playerHandler().handleSpeedChange());
        setR1Callback();
    }

    private boolean onSpeedLong() {
        mPlaybackResources.playerHandler().setPlayer(player());
        mBinding.control.action.speed.setText(mPlaybackResources.playerHandler().toggleSpeed());
        setR1Callback();
        return true;
    }

    private void onConfig() {
        HistoryDialog.create(this).readOnly().type(1).show();
        hideControl();
    }

    private void onInvert() {
        setR1Callback();
        DisplaySettings.putInvert(!DisplaySettings.isInvert());
        mBinding.control.action.invert.setActivated(DisplaySettings.isInvert());
    }

    private void onAcross() {
        setR1Callback();
        DisplaySettings.putAcross(!DisplaySettings.isAcross());
        mBinding.control.action.across.setActivated(DisplaySettings.isAcross());
    }

    private void onChange() {
        setR1Callback();
        DisplaySettings.putChange(!DisplaySettings.isChange());
        mBinding.control.action.change.setActivated(DisplaySettings.isChange());
    }

    private void onDecode() {
        mPlaybackResources.playerHandler().setPlayer(player());
        mPlaybackResources.playerHandler().handleDecodeChange();
        setR1Callback();
        setDecode();
    }

    private void onChoose() {
        PlayerHelper.choose(this, player().getUrl(), player().getHeaders(), player().isVod(), player().getPosition(), mBinding.control.title.getText());
        setRedirect(true);
    }

    private boolean onTextLong() {
        if (!player().haveTrack(C.TRACK_TYPE_TEXT)) return false;
        onSubtitleClick();
        return true;
    }

    private boolean onActionTouch(View v, MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP) setR1Callback();
        return false;
    }

    private int getLockOrient() {
        if (isLock()) {
            return ResUtil.getScreenOrientation(this);
        } else if (isRotate()) {
            return ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT;
        } else {
            return ResUtil.isLand(this) ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT;
        }
    }

    private void hideUI() {
        if (isGone(mBinding.recycler)) return;
        mBinding.recycler.setVisibility(View.GONE);
        setPosition();
    }

    private void showUI() {
        if (isVisible(mBinding.recycler) || mGroupAdapter.getItemCount() == 0) return;
        mBinding.recycler.setVisibility(View.VISIBLE);
        mBinding.channel.requestFocus();
        setPosition();
        hideEpg();
    }

    @Override
    public void showEpg(Channel item) {
        if (mChannel == null || mChannel.getData(mViewModel.getZoneId()).getList().isEmpty() || mEpgDataAdapter.getItemCount() == 0 || !mChannel.equals(item) || !mChannel.getGroup().equals(mGroup)) return;
        scrollToPosition(mBinding.epgData, item.getData(mViewModel.getZoneId()).getSelected());
        mBinding.epgData.setVisibility(View.VISIBLE);
        mBinding.channel.setVisibility(View.GONE);
        mBinding.group.setVisibility(View.GONE);
    }

    @Override
    public void hideEpg() {
        mBinding.channel.setVisibility(View.VISIBLE);
        mBinding.group.setVisibility(View.VISIBLE);
        mBinding.epgData.setVisibility(View.GONE);
    }

    private void showProgress() {
        mBinding.progress.getRoot().setVisibility(View.VISIBLE);
        App.post(mSetTrafficRunnable, 0);
        hideError();
    }

    private void hideProgress() {
        mBinding.progress.getRoot().setVisibility(View.GONE);
        App.removeCallbacks(mSetTrafficRunnable);
        Traffic.reset();
    }

    private void showError(String text) {
        mBinding.widget.error.setVisibility(View.VISIBLE);
        mBinding.widget.error.setText(text);
        hideProgress();
    }

    private void hideError() {
        mBinding.widget.error.setVisibility(View.GONE);
        mBinding.widget.error.setText("");
    }

    private void showControl() {
        if (service() == null || isInPictureInPictureMode()) return;
        mBinding.control.info.setVisibility(player().isEmpty() ? View.GONE : View.VISIBLE);
        mBinding.control.cast.setVisibility(player().isEmpty() ? View.GONE : View.VISIBLE);
        checkCastImg();
        mBinding.control.right.rotate.setVisibility(isLock() ? View.GONE : View.VISIBLE);
        mBinding.control.center.setVisibility(isLock() ? View.GONE : View.VISIBLE);
        mBinding.control.bottom.setVisibility(isLock() ? View.GONE : View.VISIBLE);
        mBinding.control.back.setVisibility(isLock() ? View.GONE : View.VISIBLE);
        mBinding.control.top.setVisibility(isLock() ? View.GONE : View.VISIBLE);
        mBinding.control.getRoot().setVisibility(View.VISIBLE);
        setR1Callback();
        hideInfo();
    }

    private void hideControl() {
        mBinding.control.getRoot().setVisibility(View.GONE);
        App.removeCallbacks(mHideControlRunnable);
    }

    private void showInfo() {
        mBinding.widget.infoPip.setVisibility(isInPictureInPictureMode() ? View.VISIBLE : View.GONE);
        mBinding.widget.overlayInfo.setVisibility(isInPictureInPictureMode() ? View.GONE : View.VISIBLE);
        setR3Callback();
        hideControl();
        setInfo();
    }

    private void hideInfo() {
        mBinding.widget.infoPip.setVisibility(View.GONE);
        mBinding.widget.overlayInfo.setVisibility(View.GONE);
        App.removeCallbacks(mHideInfoRunnable);
    }

    private void setTraffic() {
        Traffic.setSpeed(mBinding.progress.traffic);
        if (PlayerSettings.showVideoInfo() || PlayerSettings.showVideoClock()) updateVideoInfo();
        App.post(mSetTrafficRunnable, 1000);
    }

    private void updateVideoInfo() {
        PlayerManager player = player();
        if (player == null) {
            mBinding.videoInfo.setVisibility(View.GONE);
            mBinding.videoClock.setVisibility(View.GONE);
            return;
        }
        
        // Show/hide video info based on mode
        if (PlayerSettings.showVideoInfo()) {
            StringBuilder info = new StringBuilder();
            String sizeText = player.getSizeText();
            if (!sizeText.isEmpty()) info.append(sizeText);
            String codec = player.getCodecInfo();
            if (!codec.isEmpty()) {
                if (info.length() > 0) info.append(" | ");
                info.append(codec);
            }
            String decode = player.getDecodeText();
            if (!decode.isEmpty()) {
                if (info.length() > 0) info.append(" | ");
                info.append(decode);
            }
            CharSequence trafficText = mBinding.progress.traffic.getText();
            if (trafficText != null && trafficText.length() > 0) {
                if (info.length() > 0) info.append(" | ");
                info.append(trafficText);
            }
            mBinding.videoInfo.setText(info.toString());
            mBinding.videoInfo.setVisibility(View.VISIBLE);
        } else {
            mBinding.videoInfo.setVisibility(View.GONE);
        }
        
        // Show/hide clock based on mode
        if (PlayerSettings.showVideoClock()) {
            mBinding.videoClock.setVisibility(View.VISIBLE);
        } else {
            mBinding.videoClock.setVisibility(View.GONE);
        }
    }

    private void setR1Callback() {
        App.post(mHideControlRunnable, Constant.INTERVAL_HIDE);
    }

    private void setR3Callback() {
        App.post(mHideInfoRunnable, Constant.INTERVAL_HIDE);
    }

    private void onToggle() {
        if (isVisible(mBinding.control.getRoot())) hideControl();
        else if (isVisible(mBinding.recycler)) hideUI();
        else showUI();
        hideInfo();
    }

    private void resetPass() {
        this.count = 0;
    }

    private void setArtwork() {
        ImgUtil.load(this, mChannel.getLogo(), new CustomTarget<>() {
            @Override
            public void onResourceReady(@NonNull Drawable resource, @Nullable Transition<? super Drawable> transition) {
                mBinding.exo.setDefaultArtwork(resource);
            }

            @Override
            public void onLoadFailed(@Nullable Drawable errorDrawable) {
                mBinding.exo.setDefaultArtwork(errorDrawable);
            }
        });
    }

    @Override
    public void onItemClick(Group item) {
        mGroupAdapter.setSelected(mGroup = item);
        mChannelAdapter.addAll(item.getChannel());
        mChannelAdapter.setSelected(item.getPosition());
        scrollToPosition(mBinding.channel, Math.max(item.getPosition(), 0));
        if (!item.isKeep() || ++count < 5 || mHides.isEmpty()) return;
        if (Biometric.enable()) Biometric.show(this);
        else PassDialog.create().show(this);
        resetPass();
    }

    @Override
    public void onItemClick(Channel item) {
        if (!item.getData(mViewModel.getZoneId()).getList().isEmpty() && item.isSelected() && mChannel != null && mChannel.equals(item) && mChannel.getGroup().equals(mGroup)) {
            showEpg(item);
        } else if (mGroup != null) {
            mGroup.setPosition(mChannelAdapter.setSelected(item.group(mGroup)));
            mChannel = item;
            setArtwork();
            showInfo();
            hideUI();
            fetch();
        }
    }

    @Override
    public boolean onLongClick(Channel item) {
        if (mGroup.isHidden()) return false;
        boolean exist = UserLibrary.get().favoriteExists(item.getName());
        Notify.show(exist ? R.string.keep_del : R.string.keep_add);
        if (exist) delKeep(item);
        else addKeep(item);
        return true;
    }

    @Override
    public void onItemClick(EpgData item) {
        if (item.isSelected()) {
            fetch(item);
        } else if (mChannel.hasCatchup() || mChannel.isRtsp()) {
            mBinding.control.title.setText(getString(R.string.detail_title, mChannel.getShow(), item.getTitle()));
            Notify.show(getString(R.string.play_ready, item.getTitle()));
            mEpgDataAdapter.setSelected(item);
            fetch(item);
        }
    }

    private void addKeep(Channel item) {
        getKeep().add(item);
        Keep keep = new Keep();
        keep.setKey(item.getName());
        keep.setType(1);
        UserLibrary.get().saveFavorite(keep);
    }

    private void delKeep(Channel item) {
        if (mGroup.isKeep()) mChannelAdapter.remove(item);
        getKeep().getChannel().remove(item);
        UserLibrary.get().deleteLiveFavorite(item.getName());
    }

    private void setInfo() {
        mViewModel.getEpg(mChannel);
        mBinding.widget.overlayPlay.setText("");
        mBinding.widget.name.setMaxEms(48);
        mChannel.loadLogo(mBinding.widget.logo);
        mBinding.control.title.setSelected(true);
        mBinding.widget.overlayLine.setText(mChannel.getLine());
        mBinding.widget.name.setText(mChannel.getShow());
        mBinding.control.title.setText(mChannel.getShow());
        mBinding.widget.namePip.setText(mChannel.getShow());
        mBinding.widget.number.setText(mChannel.getNumber());
        mBinding.widget.numberPip.setText(mChannel.getNumber());
        mBinding.widget.overlayLine.setVisibility(mChannel.getLineVisible());
        mBinding.control.action.line.setText(mBinding.widget.overlayLine.getText());
        mBinding.control.action.line.setVisibility(mBinding.widget.overlayLine.getVisibility());
    }

    private void setEpg(Epg epg) {
        if (mChannel == null || !mChannel.getTvgId().equals(epg.getKey())) return;
        EpgData data = epg.getEpgData();
        boolean hasTitle = !data.getTitle().isEmpty();
        mEpgDataAdapter.addAll(epg.getList());
        if (hasTitle) mBinding.control.title.setText(getString(R.string.detail_title, mChannel.getShow(), data.getTitle()));
        mBinding.widget.name.setMaxEms(hasTitle ? 12 : 48);
        mBinding.widget.overlayPlay.setText(data.format());
        setWidth(epg);
        setMetadata();
    }

    private void setEpg(boolean success) {
        if (mChannel != null && success)
            mViewModel.getEpg(mChannel);
    }

    private void fetch(EpgData item) {
        if (mChannel == null) return;
        mViewModel.getUrl(mChannel, item);
        player().clear();
        player().stop();
        hideUI();
    }

    private void fetch() {
        if (mChannel == null) return;
        LiveConfig.get().setKeep(mChannel);
        mViewModel.getUrl(mChannel);
        player().clear();
        player().stop();
        showProgress();
    }

    @Override protected boolean allowsDesktopAssistance() { return true; }

    private void start(Result result) {
        mPlaybackKey = result.getRealUrl();
        startPlayer(mPlaybackKey, result, false, getHome().getTimeout(), buildMetadata());
    }

    private void checkControl() {
        if (isVisible(mBinding.control.getRoot())) showControl();
    }

    private void checkLockImg() {
        mBinding.control.right.lock.setImageResource(isLock() ? R.drawable.ic_control_lock_on : R.drawable.ic_control_lock_off);
    }

    private void checkCastImg() {
        mBinding.control.cast.setImageResource(ProjectionManager.get().getConnectedDevice() != null ? R.drawable.ic_control_cast_on : R.drawable.ic_control_cast);
    }

    private void resetAdapter() {
        mBinding.control.action.line.setVisibility(View.GONE);
        mBinding.control.title.setText("");
        mEpgDataAdapter.clear();
        mChannelAdapter.clear();
        mGroupAdapter.clear();
        mHides.clear();
        mChannel = null;
        mGroup = null;
    }

    private final PlaybackService.NavigationCallback mNavigationCallback = new PlaybackService.NavigationCallback() {
        @Override
        public void onNext() {
            nextChannel();
        }

        @Override
        public void onPrev() {
            prevChannel();
        }

        @Override
        public void onStop() {
            finish();
        }

        @Override
        public void onAudio() {
            moveTaskToBack(true);
            setAudioOnly(true);
        }
    };

    @Override
    protected void onPrepare() {
        setDecode();
    }

    @Override
    protected void onTracksChanged() {
        setTrackVisible();
    }

    @Override
    protected void onError(String msg) {
        Track.delete(player().getKey());
        player().resetTrack();
        player().reset();
        player().stop();
        showError(msg);
        startFlow();
    }

    @Override
    protected void onReclaim() {
        Result result = mViewModel.url().getValue();
        if (result != null) start(result);
    }

    @Override
    protected void onStateChanged(int state) {
        switch (state) {
            case Player.STATE_BUFFERING:
                showProgress();
                break;
            case Player.STATE_READY:
                hideProgress();
                checkControl();
                player().reset();
                break;
            case Player.STATE_ENDED:
                checkEnded();
                break;
        }
    }

    @Override
    protected void onPlayingChanged(boolean isPlaying) {
        if (isPlaying) {
            mPiP.update(this, true);
            mBinding.control.play.setImageResource(androidx.media3.ui.R.drawable.exo_icon_pause);
        } else if (isPaused()) {
            mPiP.update(this, false);
            mBinding.control.play.setImageResource(androidx.media3.ui.R.drawable.exo_icon_play);
        }
    }

    @Override
    public void onSubtitleClick() {
        postUi(this::hideControl, 200);
        postUi(() -> SubtitleDialog.create().view(mBinding.exo.getSubtitleView()).full(true).show(this), 200);
    }

    @Override
    public void setConfig(Config config) {
        Config current = LiveConfig.get().getConfig();
        LiveConfig.load(config, getCallback(current));
    }

    private Callback getCallback(Config config) {
        return lifecycleCallback(new Callback() {
            @Override
            public void start() {
                showProgress();
            }

            @Override
            public void success() {
                setLive(getHome());
            }

            @Override
            public void error(String msg) {
                LiveConfig.load(config, new Callback());
                Notify.show(msg);
                hideProgress();
            }
        });
    }

    @Override
    public void setLive(Live item) {
        if (item.isActivated()) item.getGroups().clear();
        LiveConfig.get().setHome(item);
        player().reset();
        player().clear();
        player().stop();
        resetAdapter();
        hideControl();
        getLive();
    }

    @Override
    public void setPass(String pass) {
        unlock(pass);
    }

    @Override
    public void onBiometricSuccess() {
        unlock(null);
    }

    private void unlock(String pass) {
        boolean first = true;
        Iterator<Group> iterator = mHides.iterator();
        while (iterator.hasNext()) {
            Group item = iterator.next();
            if (pass != null && !pass.equals(item.getPass())) continue;
            mGroupAdapter.add(item);
            if (first) onItemClick(item);
            iterator.remove();
            first = false;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        switch (event.getType()) {
            case LIVE -> setLive(getHome());
            case PLAYER -> fetch();
        }
    }

    private void checkEnded() {
        if (player().isLive()) {
            checkNext();
        } else {
            nextChannel();
        }
    }

    private void setTrackVisible() {
        mBinding.control.action.subtitleTracks.setVisibility(player().haveTrack(C.TRACK_TYPE_TEXT) || player().isVod() ? View.VISIBLE : View.GONE);
        mBinding.control.action.audio.setVisibility(player().haveTrack(C.TRACK_TYPE_AUDIO) ? View.VISIBLE : View.GONE);
        mBinding.control.action.videoTracks.setVisibility(player().haveTrack(C.TRACK_TYPE_VIDEO) ? View.VISIBLE : View.GONE);
        mBinding.control.action.speed.setVisibility(player().isVod() ? View.VISIBLE : View.GONE);
    }

    private MediaMetadata buildMetadata() {
        String artist = mBinding.widget.overlayPlay.getText().toString();
        return PlayerManager.buildMetadata(mChannel.getShow(), artist, mChannel.getLogo());
    }

    private void setMetadata() {
        player().setMetadata(buildMetadata());
    }

    private void startFlow() {
        if (!DisplaySettings.isChange()) return;
        if (!mChannel.isLast()) nextLine(true);
    }

    private void prevChannel() {
        moveChannel(-1);
    }

    private void nextChannel() {
        moveChannel(1);
    }

    private void moveChannel(int direction) {
        if (mGroup == null) return;
        LiveSessionNavigator.Selection selection = LiveSessionNavigator.move(
                mGroupAdapter.getItems(), mGroupAdapter.indexOf(mGroup), mGroup.getPosition(),
                direction, DisplaySettings.isAcross());
        if (selection.groupIndex() < 0 || selection.channelIndex() < 0) return;
        Group selectedGroup = mGroupAdapter.get(selection.groupIndex());
        if (selectedGroup != mGroup) {
            mGroup = selectedGroup;
            mGroupAdapter.setSelected(selection.groupIndex());
            mChannelAdapter.addAll(mGroup.getChannel());
        }
        mGroup.setPosition(selection.channelIndex());
        Channel current = mGroup.current();
        if (current != null) onItemClick(current);
    }

    private void checkNext() {
        int current = mChannel.getData(mViewModel.getZoneId()).getInRange();
        int position = mChannel.getData(mViewModel.getZoneId()).getSelected() + 1;
        boolean hasNext = position <= current && position > 0;
        if (hasNext) onItemClick(mChannel.getData(mViewModel.getZoneId()).getList().get(position));
        else fetch();
    }

    private void nextLine(boolean show) {
        if (mChannel == null || mChannel.isOnly()) return;
        mChannel.switchLine(true);
        if (show) showInfo();
        else setInfo();
        fetch();
    }

    private void onPaused() {
        controller().pause();
    }

    private void onPlay() {
        if (isVisible(mBinding.widget.error)) fetch();
        else controller().play();
    }

    public boolean isRotate() {
        return rotate;
    }

    public void setRotate(boolean rotate) {
        this.rotate = rotate;
        if (rotate) {
            noPadding(mBinding.recycler);
            noPadding(mBinding.control.getRoot());
        } else {
            setPadding(mBinding.recycler, true);
            setPadding(mBinding.control.getRoot());
        }
    }

    private void scrollToPosition(RecyclerView view, int position) {
        view.post(() -> view.scrollToPosition(position));
    }

    @Override
    public void onSpeedUp() {
        if (player().isLive()) return;
        if (!player().isPlaying()) return;
        mBinding.widget.overlaySpeed.setVisibility(View.VISIBLE);
        mBinding.widget.overlaySpeed.startAnimation(ResUtil.getAnim(R.anim.forward));
        mBinding.control.action.speed.setText(player().setSpeed(PlayerSettings.getSpeed()));
    }

    @Override
    public void onSpeedEnd() {
        mBinding.widget.overlaySpeed.clearAnimation();
        mBinding.control.action.speed.setText(player().setSpeed(1.0f));
    }

    @Override
    public void onBright(int progress) {
        mBinding.widget.bright.setVisibility(View.VISIBLE);
        mBinding.widget.brightProgress.setProgress(progress);
        if (progress < 35) mBinding.widget.brightIcon.setImageResource(R.drawable.ic_widget_bright_low);
        else if (progress < 70) mBinding.widget.brightIcon.setImageResource(R.drawable.ic_widget_bright_medium);
        else mBinding.widget.brightIcon.setImageResource(R.drawable.ic_widget_bright_high);
    }

    @Override
    public void onVolume(int progress) {
        mBinding.widget.volume.setVisibility(View.VISIBLE);
        mBinding.widget.volumeProgress.setProgress(progress);
        if (progress < 35) mBinding.widget.volumeIcon.setImageResource(R.drawable.ic_widget_volume_low);
        else if (progress < 70) mBinding.widget.volumeIcon.setImageResource(R.drawable.ic_widget_volume_medium);
        else mBinding.widget.volumeIcon.setImageResource(R.drawable.ic_widget_volume_high);
    }

    @Override
    public void onFlingUp() {
        if (DisplaySettings.isInvert()) nextChannel();
        else prevChannel();
    }

    @Override
    public void onFlingDown() {
        if (DisplaySettings.isInvert()) prevChannel();
        else nextChannel();
    }

    @Override
    public long getVideoDuration() {
        return player().getDuration();
    }

    @Override
    public void onSeeking(long time) {
        if (player().isLive()) return;
        mBinding.widget.overlayAction.setImageResource(time > 0 ? R.drawable.ic_widget_forward : R.drawable.ic_widget_rewind);
        mBinding.widget.time.setText(player().getPositionTime(time));
        mBinding.widget.overlaySeek.setVisibility(View.VISIBLE);
        hideProgress();
    }

    @Override
    public void onSeekEnd(long time) {
        if (player().isLive()) return;
        seekTo(time);
    }

    @Override
    public void onSingleTap() {
        onToggle();
    }

    @Override
    public void onDoubleTap() {
        if (isVisible(mBinding.recycler)) hideUI();
        if (isVisible(mBinding.control.getRoot())) hideControl();
        else showControl();
    }

    @Override
    public void onDoubleTapSeek(long time) {
        onDoubleTap();
    }

    @Override
    public void onTouchEnd() {
        mBinding.widget.overlaySeek.setVisibility(View.GONE);
        mBinding.widget.overlaySpeed.setVisibility(View.GONE);
        mBinding.widget.bright.setVisibility(View.GONE);
        mBinding.widget.volume.setVisibility(View.GONE);
    }

    @Override
    public void onShare(CharSequence title) {
        PlayerHelper.share(this, player().getUrl(), player().getHeaders(), title);
        setRedirect(true);
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (isRedirect()) return;
        if (isLock()) postUi(this::onLock, 500);
        if (player().haveTrack(C.TRACK_TYPE_VIDEO)) mPiP.enter(this, player().getVideoWidth(), player().getVideoHeight(), DisplaySettings.getLiveScale());
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, @NonNull Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        if (isInPictureInPictureMode) {
            hideControl();
            hideInfo();
            hideUI();
        } else {
            hideInfo();
            if (isStop()) finish();
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Util.hideSystemUI(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Util.hideSystemUI(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mPlaybackResources.videoClock().stop().start();
        setAudioOnly(false);
        setStop(false);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (PlayerSettings.isBackgroundOff()) mPlaybackResources.videoClock().stop();
        if (!isAudioOnly()) setStop(true);
    }

    @Override
    protected void onBackInvoked() {
        if (isVisible(mBinding.control.getRoot())) {
            hideControl();
        } else if (isVisible(mBinding.widget.overlayInfo)) {
            hideInfo();
        } else if (isVisible(mBinding.recycler)) {
            hideUI();
        } else if (!isLock()) {
            startHomeAsRoot();
            super.onBackInvoked();
        }
    }

    @Override
    protected void onDestroy() {
        if (mPlaybackResources != null) mPlaybackResources.release();
        if (mViewModel != null) {
            mViewModel.url().removeObservers(this);
            mViewModel.xml().removeObservers(this);
            mViewModel.epg().removeObservers(this);
            mViewModel.live().removeObservers(this);
            mViewModel = null;
        }
        Source.get().exit();
        App.removeCallbacks(mHideControlRunnable, mSetTrafficRunnable, mHideInfoRunnable);
        mBinding = null;
        mKeyDown = null;
        mLiveController = null;
        mPlaybackResources = null;
        mChannelAdapter = null;
        mEpgDataAdapter = null;
        mGroupAdapter = null;
        mObserveUrl = null;
        mObserveEpg = null;
        mHides = null;
        mChannel = null;
        mGroup = null;
        mHideControlRunnable = null;
        mSetTrafficRunnable = null;
        mHideInfoRunnable = null;
        mPiP = null;
        super.onDestroy();
    }
}
