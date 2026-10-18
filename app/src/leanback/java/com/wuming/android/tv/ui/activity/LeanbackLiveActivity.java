package com.wuming.android.tv.ui.activity;
import com.wuming.android.tv.setting.DisplaySettings;
import com.wuming.android.tv.setting.PlayerSettings;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.KeyEvent;
import com.wuming.android.tv.utils.TvRemoteKeys;
import com.wuming.android.tv.utils.TvRemoteMappings;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.leanback.widget.OnChildViewHolderSelectedListener;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.C;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.bumptech.glide.request.transition.Transition;
import com.wuming.android.tv.App;
import com.wuming.android.tv.Constant;
import com.wuming.android.tv.R;
import com.wuming.android.tv.api.config.LiveConfig;
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
import com.wuming.android.tv.ui.adapter.LeanbackGroupAdapter;
import com.wuming.android.tv.ui.base.PlaybackActivity;
import com.wuming.android.tv.ui.custom.CustomKeyDownLive;
import com.wuming.android.tv.ui.custom.CustomLiveListView;
import com.wuming.android.tv.ui.custom.CustomSeekView;
import com.wuming.android.tv.ui.dialog.HistoryDialog;
import com.wuming.android.tv.ui.dialog.InfoDialog;
import com.wuming.android.tv.ui.dialog.LiveDialog;
import com.wuming.android.tv.ui.dialog.PassDialog;
import com.wuming.android.tv.ui.dialog.SubtitleDialog;
import com.wuming.android.tv.ui.dialog.TrackDialog;
import com.wuming.android.tv.ui.controller.LiveController;
import com.wuming.android.tv.ui.base.LivePlaybackResources;
import com.wuming.android.tv.ui.base.LiveSessionNavigator;
import com.wuming.android.tv.utils.ImgUtil;
import com.wuming.android.tv.bean.CastVideo;
import com.wuming.android.tv.ui.dialog.CastDialogV2;
import com.wuming.android.tv.utils.Notify;
import com.wuming.android.tv.utils.ResUtil;
import com.wuming.android.tv.utils.Traffic;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class LeanbackLiveActivity extends PlaybackActivity implements LeanbackGroupAdapter.OnClickListener, ChannelAdapter.OnClickListener, EpgDataAdapter.OnClickListener, CustomKeyDownLive.Listener, CustomLiveListView.Callback, TrackDialog.Listener, PassCallback, ConfigCallback, LiveCallback, InfoDialog.Listener {

    private ActivityLiveBinding mBinding;
    private LiveController mLiveController;
    private LivePlaybackResources mPlaybackResources;
    private ChannelAdapter mChannelAdapter;
    private EpgDataAdapter mEpgDataAdapter;
    private LeanbackGroupAdapter mGroupAdapter;
    private Observer<Result> mObserveUrl;
    private CustomKeyDownLive mKeyDown;
    private Observer<Epg> mObserveEpg;
    private LiveViewModel mViewModel;
    private List<Group> mHides;
    private String mPlaybackKey;
    private Channel mChannel;
    private View mOldView;
    private Group mGroup;
    private Runnable mSetActivatedRunnable;
    private Runnable mHideControlRunnable;
    private Runnable mSetTrafficRunnable;
    private Runnable mHideInfoRunnable;
    private Runnable mHideUIRunnable;
    private View mFocus2;
    private int count;

    public static void start(Context context) {
        context.startActivity(new Intent(context, LeanbackLiveActivity.class).putExtra("empty", LiveConfig.isEmpty()));
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
        return mBinding.control.seek;
    }

    @Override
    protected void onServiceConnected() {
        mBinding.control.action.speed.setText(player().getSpeedText());
        mBinding.control.action.decode.setText(player().getDecodeText());
        checkLive();
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        super.initView(savedInstanceState);
        ResUtil.scalePlayerOverlay(mBinding.videoInfo, mBinding.videoClock);
        mLiveController = new LiveController(this);
        mPlaybackResources = new LivePlaybackResources(this, mBinding.widget.clock, mBinding.videoClock);
        mKeyDown = CustomKeyDownLive.create(this);
        mObserveEpg = this::setEpg;
        mObserveUrl = this::start;
        mHides = new ArrayList<>();
        mSetActivatedRunnable = this::setActivated;
        mHideControlRunnable = this::hideControl;
        mSetTrafficRunnable = this::setTraffic;
        mHideInfoRunnable = this::hideInfo;
        mHideUIRunnable = this::hideUI;
        setRecyclerView();
        setVideoView();
        setViewModel();
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    protected void initEvent() {
        mBinding.group.setListener(this);
        mBinding.channel.setListener(this);
        mBinding.epgData.setListener(this);
        mBinding.control.action.subtitleTracks.setOnClickListener(this::onTrack);
        mBinding.control.action.audio.setOnClickListener(this::onTrack);
        mBinding.control.action.videoTracks.setOnClickListener(this::onTrack);
        mBinding.control.action.speed.setUpListener(this::onSpeedAdd);
        mBinding.control.action.speed.setDownListener(this::onSpeedSub);
        mBinding.control.action.subtitleTracks.setUpListener(this::onSubtitleClick);
        mBinding.control.action.subtitleTracks.setDownListener(this::onSubtitleClick);
        mBinding.control.action.home.setOnClickListener(view -> onHome());
        mBinding.control.action.line.setOnClickListener(view -> onLine());
        mBinding.control.action.scale.setOnClickListener(view -> onScale());
        mBinding.control.action.speed.setOnClickListener(view -> onSpeed());
        mBinding.control.action.config.setOnClickListener(view -> onConfig());
        mBinding.control.action.playbackAction.setOnClickListener(view -> onAction());
        mBinding.control.action.invert.setOnClickListener(view -> onInvert());
        mBinding.control.action.across.setOnClickListener(view -> onAcross());
        mBinding.control.action.change.setOnClickListener(view -> onChange());
        mBinding.control.action.player.setOnClickListener(view -> onChoose());
        mBinding.control.action.decode.setOnClickListener(view -> onDecode());
        mBinding.control.action.cast.setOnClickListener(view -> onCast());
        mBinding.control.action.actionInfo.setOnClickListener(view -> onVideoInfo());
        mBinding.control.action.actionInfo.setOnLongClickListener(view -> onInfo());
        mBinding.control.action.actionInfo.setActivated(PlayerSettings.getVideoInfo() != 3);
        mBinding.control.action.speed.setOnLongClickListener(view -> onSpeedLong());
        mBinding.video.setOnTouchListener((view, event) -> mKeyDown.onTouchEvent(event));
        mBinding.group.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                if (mGroupAdapter.getItemCount() > 0) onChildSelected(child, mGroup = mGroupAdapter.get(position));
            }
        });
    }

    private void setRecyclerView() {
        mBinding.group.setItemAnimator(null);
        mBinding.channel.setItemAnimator(null);
        mBinding.epgData.setItemAnimator(null);
        mBinding.group.setAdapter(mGroupAdapter = new LeanbackGroupAdapter(this));
        mBinding.channel.setAdapter(mChannelAdapter = new ChannelAdapter(this));
        mBinding.epgData.setAdapter(mEpgDataAdapter = new EpgDataAdapter(this));
    }

    private void setVideoView() {
        setScale(DisplaySettings.getLiveScale());
        findViewById(R.id.timeBar).setNextFocusUpId(R.id.config);
        mBinding.control.action.invert.setActivated(DisplaySettings.isInvert());
        mBinding.control.action.across.setActivated(DisplaySettings.isAcross());
        mBinding.control.action.change.setActivated(DisplaySettings.isChange());
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

    private Group setWidth(Group group) {
        int width = mLiveController.calculateChannelWidth(group);
        setWidth(mBinding.channel, width);
        return group;
    }

    private void setWidth(Epg epg) {
        int width = mLiveController.calculateEpgWidth(epg);
        if (width == 0) return;
        setWidth(mBinding.epgData, width);
    }

    private void setWidth(View view, int width) {
        view.post(() -> mLiveController.applyWidth(view, width));
    }

    private void setPosition(int[] position) {
        if (position[0] == -1) return;
        int size = mGroupAdapter.getItemCount();
        if (size == 1 || position[0] >= size) return;
        mGroup = mGroupAdapter.get(position[0]);
        mBinding.group.setSelectedPosition(position[0]);
        mGroup.setPosition(position[1]);
        onItemClick(mGroup);
        Channel current = mGroup.current();
        if (current != null) onItemClick(current);
    }

    private void setPosition() {
        if (mChannel == null) return;
        mGroup = mChannel.getGroup();
        int position = mGroupAdapter.indexOf(mGroup);
        boolean change = mBinding.group.getSelectedPosition() != position;
        if (change) mBinding.group.setSelectedPosition(position);
        if (change) mChannelAdapter.addAll(mGroup.getChannel());
        mBinding.channel.setSelectedPosition(mGroup.getPosition());
    }

    private void onChildSelected(@Nullable RecyclerView.ViewHolder child, Group group) {
        if (mOldView != null) mOldView.setSelected(false);
        if ((mOldView = child != null ? child.itemView : null) == null) return;
        mOldView.setSelected(true);
        onItemClick(group);
        resetPass();
    }

    private void setActivated() {
        mChannelAdapter.setSelected(mChannel);
        notifyItemChanged(mBinding.channel, mChannelAdapter);
        fetch();
    }

    private void setActivated(EpgData item) {
        mEpgDataAdapter.setSelected(item);
        notifyItemChanged(mBinding.epgData, mEpgDataAdapter);
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
        setScale(index == array.length - 1 ? 0 : ++index);
    }

    private void onSpeed() {
        mPlaybackResources.playerHandler().setPlayer(player());
        mBinding.control.action.speed.setText(mPlaybackResources.playerHandler().handleSpeedChange());
    }

    private void onSpeedAdd() {
        mBinding.control.action.speed.setText(player().addSpeed(0.25f));
    }

    private void onSpeedSub() {
        mBinding.control.action.speed.setText(player().subSpeed(0.25f));
    }

    private boolean onSpeedLong() {
        mPlaybackResources.playerHandler().setPlayer(player());
        mBinding.control.action.speed.setText(mPlaybackResources.playerHandler().toggleSpeed());
        return true;
    }

    private void onConfig() {
        HistoryDialog.create(this).readOnly().type(1).show();
        hideControl();
    }

    private void onAction() {
        checkPlay();
    }

    private void onInvert() {
        DisplaySettings.putInvert(!DisplaySettings.isInvert());
        mBinding.control.action.invert.setActivated(DisplaySettings.isInvert());
    }

    private void onAcross() {
        DisplaySettings.putAcross(!DisplaySettings.isAcross());
        mBinding.control.action.across.setActivated(DisplaySettings.isAcross());
    }

    private void onChange() {
        DisplaySettings.putChange(!DisplaySettings.isChange());
        mBinding.control.action.change.setActivated(DisplaySettings.isChange());
    }

    private void onChoose() {
        PlayerHelper.choose(this, player().getUrl(), player().getHeaders(), player().isVod(), player().getPosition(), mBinding.widget.overlayTitle.getText());
        setRedirect(true);
    }

    private void onDecode() {
        mPlaybackResources.playerHandler().setPlayer(player());
        mPlaybackResources.playerHandler().handleDecodeChange();
        setDecode();
    }

    private void hideUI() {
        App.removeCallbacks(mHideUIRunnable);
        if (isGone(mBinding.recycler)) return;
        mBinding.recycler.setVisibility(View.GONE);
        setPosition();
    }

    private void showUI() {
        if (isVisible(mBinding.recycler) || mGroupAdapter.getItemCount() == 0) return;
        mBinding.recycler.setVisibility(View.VISIBLE);
        setPosition();
        setUITimer();
        hideEpg();
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
            mBinding.control.action.playbackAction.setText(R.string.pause);
        } else if (isPaused()) {
            mBinding.control.action.playbackAction.setText(R.string.play);
        }
    }

    @Override
    public void showEpg(Channel item) {
        if (mChannel == null || mChannel.getData(mViewModel.getZoneId()).getList().isEmpty() || mEpgDataAdapter.getItemCount() == 0 || !mChannel.equals(item) || !mChannel.getGroup().equals(mGroup)) return;
        mBinding.epgData.setSelectedPosition(mChannel.getData(mViewModel.getZoneId()).getSelected());
        mBinding.epgData.setVisibility(View.VISIBLE);
        mBinding.channel.setVisibility(View.GONE);
        mBinding.group.setVisibility(View.GONE);
        mBinding.epgData.requestFocus();
    }

    @Override
    public void hideEpg() {
        mBinding.channel.setVisibility(View.VISIBLE);
        mBinding.group.setVisibility(View.VISIBLE);
        mBinding.epgData.setVisibility(View.GONE);
        mBinding.channel.requestFocus();
    }

    private void showProgress() {
        mBinding.progress.getRoot().setVisibility(View.VISIBLE);
        App.post(mSetTrafficRunnable, 0);
        hideCenter();
        hideError();
    }

    private void hideProgress() {
        mBinding.progress.getRoot().setVisibility(View.GONE);
        App.removeCallbacks(mSetTrafficRunnable);
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

    private void showControl(View view) {
        mBinding.control.getRoot().setVisibility(View.VISIBLE);
        mBinding.widget.top.setVisibility(View.VISIBLE);
        mBinding.videoInfo.setVisibility(View.GONE);
        mBinding.videoClock.setVisibility(View.GONE);
        postUi(view::requestFocus, 25);
        setR1Callback();
        hideInfo();
    }

    private void hideControl() {
        mBinding.control.getRoot().setVisibility(View.GONE);
        mBinding.widget.top.setVisibility(View.GONE);
        if (PlayerSettings.showVideoInfo()) mBinding.videoInfo.setVisibility(View.VISIBLE);
        if (PlayerSettings.showVideoClock()) mBinding.videoClock.setVisibility(View.VISIBLE);
        App.removeCallbacks(mHideControlRunnable);
    }

    private void hideCenter() {
        mBinding.widget.overlayAction.setImageResource(R.drawable.ic_widget_play);
        mBinding.widget.center.setVisibility(View.GONE);
    }

    private void showInfo() {
        mBinding.widget.bottom.setVisibility(View.VISIBLE);
        setR3Callback();
        setInfo();
    }

    private void hideInfo() {
        mBinding.widget.bottom.setVisibility(View.GONE);
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

    private void onVideoInfo() {
        int currentMode = PlayerSettings.getVideoInfo();
        int newMode = (currentMode + 1) % 4;
        PlayerSettings.putVideoInfo(newMode);
        mBinding.control.action.actionInfo.setActivated(newMode != 3);
        updateVideoInfo();
    }

    private boolean onInfo() {
        InfoDialog.create(this).title(mBinding.widget.overlayTitle.getText()).headers(player().getHeaders()).url(player().getUrl()).show();
        return true;
    }

    @Override
    public void onShare(CharSequence title) {
    }

    private void onCast() {
        // Pause immediately when casting is chosen; discovery and handoff may take time or fail.
        player().pause();
        CastDialogV2.create().video(new CastVideo(mBinding.widget.overlayTitle.getText().toString(), player().getUrl(), C.TIME_UNSET, player().getHeaders())).player(player()).onCasted(() -> {
            player().pause();
        }).show(this);
        hideControl();
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
        mChannelAdapter.addAll(setWidth(item).getChannel());
        mBinding.channel.setSelectedPosition(Math.max(item.getPosition(), 0));
        if (!item.isKeep() || ++count < 5 || mHides.isEmpty()) return;
        PassDialog.create().show(this);
        App.removeCallbacks(mHideUIRunnable);
        resetPass();
    }

    @Override
    public void onItemClick(Channel item) {
        if (!item.getData(mViewModel.getZoneId()).getList().isEmpty() && item.isSelected() && mChannel != null && mChannel.equals(item) && mChannel.getGroup().equals(mGroup)) {
            showEpg(item);
        } else if (mGroup != null) {
            mGroup.setPosition(mBinding.channel.getSelectedPosition());
            setChannel(item.group(mGroup));
            hideUI();
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
            mBinding.widget.overlayTitle.setText(getString(R.string.detail_title, mChannel.getShow(), item.getTitle()));
            Notify.show(getString(R.string.play_ready, item.getTitle()));
            setActivated(item);
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
        if (mChannelAdapter.getItemCount() == 0) mBinding.group.requestFocus();
        getKeep().getChannel().remove(item);
        UserLibrary.get().deleteLiveFavorite(item.getName());
    }

    private void setChannel(Channel item) {
        App.post(mSetActivatedRunnable, 100);
        mChannel = item;
        setArtwork();
        showInfo();
    }

    private void setInfo() {
        mViewModel.getEpg(mChannel);
        mBinding.widget.overlayPlay.setText("");
        mBinding.widget.name.setMaxEms(48);
        mChannel.loadLogo(mBinding.widget.logo);
        mBinding.widget.overlayTitle.setSelected(true);
        mBinding.widget.overlayLine.setText(mChannel.getLine());
        mBinding.widget.name.setText(mChannel.getShow());
        mBinding.widget.overlayTitle.setText(mChannel.getShow());
        mBinding.control.action.line.setText(mChannel.getLine());
        mBinding.widget.number.setText(mChannel.getNumber());
        mBinding.widget.overlayLine.setVisibility(mChannel.getLineVisible());
        mBinding.control.action.line.setVisibility(mChannel.getLineVisible());
    }

    private void setEpg(Epg epg) {
        if (mChannel == null || !mChannel.getTvgId().equals(epg.getKey())) return;
        EpgData data = epg.getEpgData();
        boolean hasTitle = !data.getTitle().isEmpty();
        mEpgDataAdapter.addAll(epg.getList());
        if (hasTitle) mBinding.widget.overlayTitle.setText(getString(R.string.detail_title, mChannel.getShow(), data.getTitle()));
        mBinding.widget.name.setMaxEms(hasTitle ? 12 : 48);
        mBinding.widget.overlayPlay.setText(data.format());
        setWidth(epg);
        setMetadata();
    }

    private void setEpg(boolean success) {
        if (mChannel != null && success) mViewModel.getEpg(mChannel);
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

    private void resetAdapter() {
        mBinding.control.action.line.setVisibility(View.GONE);
        mBinding.widget.overlayTitle.setText("");
        mEpgDataAdapter.clear();
        mChannelAdapter.clear();
        mGroupAdapter.clear();
        mHides.clear();
        mChannel = null;
        mGroup = null;
    }

    @Override
    public void onSubtitleClick() {
        SubtitleDialog.create().view(mBinding.exo.getSubtitleView()).full(true).show(this);
        postUi(this::hideControl, 100);
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

    private void unlock(String pass) {
        boolean first = true;
        int position = mGroupAdapter.getItemCount();
        Iterator<Group> iterator = mHides.iterator();
        while (iterator.hasNext()) {
            Group item = iterator.next();
            if (pass != null && !pass.equals(item.getPass())) continue;
            mGroupAdapter.add(mGroupAdapter.getItemCount(), item);
            if (first) mBinding.group.setSelectedPosition(position);
            if (first) onItemClick(mGroup = item);
            iterator.remove();
            first = false;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        switch (event.getType()) {
            case LIVE:
                setLive(getHome());
                break;
            case PLAYER:
                fetch();
                break;
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
            mBinding.group.setSelectedPosition(selection.groupIndex());
            mChannelAdapter.addAll(mGroup.getChannel());
        }
        mGroup.setPosition(selection.channelIndex());
        Channel current = mGroup.current();
        if (current != null) setChannel(current);
    }

    private void checkNext() {
        int current = mChannel.getData(mViewModel.getZoneId()).getInRange();
        int position = mChannel.getData(mViewModel.getZoneId()).getSelected() + 1;
        boolean hasNext = position <= current && position > 0;
        if (hasNext) onItemClick(mChannel.getData(mViewModel.getZoneId()).getList().get(position));
        else fetch();
    }

    private void prevLine() {
        if (mChannel == null || mChannel.isOnly()) return;
        mChannel.switchLine(false);
        showInfo();
        fetch();
    }

    private void nextLine(boolean show) {
        if (mChannel == null || mChannel.isOnly()) return;
        mChannel.switchLine(true);
        if (show) showInfo();
        else setInfo();
        fetch();
    }

    private void seek(long time) {
        mKeyDown.reset();
        seekTo(time);
    }

    private void onPaused() {
        controller().pause();
    }

    private void onPlay() {
        if (isVisible(mBinding.widget.error)) fetch();
        else controller().play();
    }

    private View getFocus2() {
        return mFocus2 == null || mFocus2.getVisibility() != View.VISIBLE ? mBinding.control.action.config : mFocus2;
    }

    private TvRemoteMappings remoteMappings;

    private void onRemoteCommand(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_MENU: onMenu(); break;
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
        if (isVisible(mBinding.control.getRoot())) mFocus2 = getCurrentFocus();
        if (mKeyDown.hasEvent(event) && service() != null && mKeyDown.onKeyDown(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void setUITimer() {
        App.post(mHideUIRunnable, Constant.INTERVAL_HIDE);
    }

    @Override
    public boolean dispatch(boolean check) {
        return !check || (isGone(mBinding.recycler) && isGone(mBinding.control.getRoot()));
    }

    @Override
    public void onShow(String number) {
        mBinding.widget.digital.setText(number);
        mBinding.widget.digital.setVisibility(number.isEmpty() ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onFind(String number) {
        mBinding.widget.digital.setVisibility(View.GONE);
        setPosition(LiveConfig.get().findByChannelNumber(number, mGroupAdapter.unmodifiableList()));
    }

    @Override
    public void onSeeking(long time) {
        if (player().isLive()) return;
        mBinding.widget.center.setVisibility(View.VISIBLE);
        mBinding.widget.duration.setText(player().getDurationTime());
        mBinding.widget.position.setText(player().getPositionTime(time));
        mBinding.widget.overlayAction.setImageResource(time > 0 ? R.drawable.ic_widget_forward : R.drawable.ic_widget_rewind);
        hideProgress();
    }

    @Override
    public void onKeyUp() {
        if (DisplaySettings.isInvert()) nextChannel();
        else prevChannel();
    }

    @Override
    public void onKeyDown() {
        if (DisplaySettings.isInvert()) prevChannel();
        else nextChannel();
    }

    @Override
    public void onKeyLeft(long time) {
        if (player().isLive()) prevLine();
        else postUi(() -> seek(time), 250);
    }

    @Override
    public void onKeyRight(long time) {
        if (player().isLive()) nextLine(true);
        else postUi(() -> seek(time), 250);
    }

    @Override
    public void onKeyCenter() {
        hideInfo();
        showUI();
    }

    @Override
    public void onMenu() {
        showControl(getFocus2());
    }

    @Override
    public void onSingleTap() {
        onToggle();
    }

    @Override
    public void onDoubleTap() {
        if (isVisible(mBinding.recycler)) hideUI();
        else if (isVisible(mBinding.control.getRoot())) hideControl();
        else onMenu();
    }

    @Override
    protected void onStart() {
        super.onStart();
        mPlaybackResources.clock().stop().start();
        mPlaybackResources.videoClock().stop().start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (PlayerSettings.isBackgroundOff()) mPlaybackResources.clock().stop();
        if (PlayerSettings.isBackgroundOff()) mPlaybackResources.videoClock().stop();
    }

    @Override
    protected void onBackInvoked() {
        if (isVisible(mBinding.control.getRoot())) {
            hideControl();
        } else if (isVisible(mBinding.widget.bottom)) {
            hideInfo();
        } else if (isVisible(mBinding.recycler)) {
            hideUI();
        } else {
            startHomeAsRoot();
            super.onBackInvoked();
        }
    }

    @Override
    protected void onDestroy() {
        if (mKeyDown != null) mKeyDown.release();
        if (mPlaybackResources != null) mPlaybackResources.release();
        if (mViewModel != null) {
            mViewModel.url().removeObservers(this);
            mViewModel.xml().removeObservers(this);
            mViewModel.epg().removeObservers(this);
            mViewModel.live().removeObservers(this);
            mViewModel = null;
        }
        Source.get().exit();
        App.removeCallbacks(mSetActivatedRunnable, mHideControlRunnable, mSetTrafficRunnable, mHideInfoRunnable, mHideUIRunnable);
        super.onDestroy();
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
        mOldView = null;
        mSetActivatedRunnable = null;
        mHideControlRunnable = null;
        mSetTrafficRunnable = null;
        mHideInfoRunnable = null;
        mHideUIRunnable = null;
    }
}
