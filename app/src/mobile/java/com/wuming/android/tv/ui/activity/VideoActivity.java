package com.wuming.android.tv.ui.activity;
import com.wuming.android.tv.setting.PlayerSettings;
import com.wuming.android.tv.setting.AppSettings;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import androidx.fragment.app.FragmentActivity;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.style.ClickableSpan;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.C;
import androidx.media3.common.VideoSize;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.transition.ChangeBounds;
import androidx.transition.TransitionManager;
import androidx.viewbinding.ViewBinding;

import com.wuming.android.tv.App;
import com.wuming.android.tv.R;
import com.wuming.android.tv.bean.Episode;
import com.wuming.android.tv.bean.EpisodeShortName;
import com.wuming.android.tv.bean.Flag;
import com.wuming.android.tv.bean.History;
import com.wuming.android.tv.bean.Parse;
import com.wuming.android.tv.bean.Result;
import com.wuming.android.tv.bean.Vod;
import com.wuming.android.tv.databinding.ActivityVideoBinding;
import com.wuming.android.tv.event.CastEvent;
import com.wuming.android.tv.player.PlayerHelper;
import com.wuming.android.tv.player.PlayerManager;
import com.wuming.android.tv.service.PlaybackService;
import com.wuming.android.tv.ui.adapter.EpisodeAdapter;
import com.wuming.android.tv.ui.adapter.FlagAdapter;
import com.wuming.android.tv.ui.adapter.ParseAdapter;
import com.wuming.android.tv.ui.adapter.QualityAdapter;
import com.wuming.android.tv.ui.adapter.QuickAdapter;
import com.wuming.android.tv.ui.base.BaseVideoActivity;
import com.wuming.android.tv.ui.base.BufferingCountdown;
import com.wuming.android.tv.ui.base.PlaybackOverlayState;
import com.wuming.android.tv.ui.base.ViewType;
import com.wuming.android.tv.ui.custom.CustomKeyDown;
import com.wuming.android.tv.ui.custom.CustomSeekView;
import com.wuming.android.tv.ui.custom.SpaceItemDecoration;
import com.wuming.android.tv.api.projection.ProjectionManager;
import com.wuming.android.tv.ui.dialog.ControlDialog;
import com.wuming.android.tv.ui.dialog.EpisodeGridDialog;
import com.wuming.android.tv.ui.dialog.EpisodeListDialog;
import com.wuming.android.tv.ui.dialog.InfoDialog;
import com.wuming.android.tv.ui.dialog.VideoRankDialog;
import com.wuming.android.tv.ui.dialog.ReceiveDialog;
import com.wuming.android.tv.ui.dialog.SubtitleDialog;
import com.wuming.android.tv.ui.dialog.TrackDialog;
import com.wuming.android.tv.ui.controller.VideoController;
import com.wuming.android.tv.utils.Clock;
import com.wuming.android.tv.utils.LayoutConfig;
import com.wuming.android.tv.utils.Notify;
import com.wuming.android.tv.utils.PiP;
import com.wuming.android.tv.utils.ResUtil;
import com.wuming.android.tv.utils.EpisodeDisplayHelper;
import com.wuming.android.tv.utils.VideoRankManager;
import com.wuming.android.tv.utils.Timer;
import com.wuming.android.tv.utils.Traffic;
import com.wuming.android.tv.utils.UiSafe;
import com.wuming.android.tv.utils.Util;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public class VideoActivity extends BaseVideoActivity implements CustomKeyDown.Listener, TrackDialog.Listener, ControlDialog.Listener, FlagAdapter.OnClickListener, EpisodeAdapter.OnClickListener, QualityAdapter.OnClickListener, QuickAdapter.OnClickListener, ParseAdapter.OnClickListener, InfoDialog.Listener, ProjectionManager.CastSessionListener, CastController.Host, DanmakuController.Host, SpeedScaleController.Host, SkipMarkerController.Host {

    private ValueAnimator mAnimator;
    private VideoController mVideoController;
    private CustomKeyDown mKeyDown;
    private boolean rotate;
    private boolean uiStarted;
    private Runnable mSetTrafficRunnable;
    private Runnable mSetOrientRunnable;
    private CastController mCast;
    private DanmakuController mDanmaku;
    private SpeedScaleController mSpeedScale;
    private SkipMarkerController mSkipMarker;
    private PiP mPiP;
    private final BufferingCountdown mBufferingCountdown = new BufferingCountdown();
    private String mBufferingStatus = "";
    private boolean mPlaybackWasReady;

    public static void cast(Activity activity, History history) {
        cast(activity, history, null);
    }

    public static void cast(Activity activity, History history, String audioTrackFormat) {
        Intent intent = new Intent(activity, VideoActivity.class);
        intent.putExtra("collect", false);
        intent.putExtra("mark", (String) null);
        intent.putExtra("name", history.getVodName());
        intent.putExtra("pic", history.getVodPic());
        intent.putExtra("key", history.getSiteKey());
        intent.putExtra("id", history.getVodId());
        if (audioTrackFormat != null) intent.putExtra("audioTrackFormat", audioTrackFormat);
        activity.startActivity(intent);
    }

    public static void collect(Activity activity, String key, String id, String name, String pic) {
        start(activity, key, id, name, pic, null, true);
    }

    public static void start(Activity activity, String key, String id, String name, String pic) {
        start(activity, key, id, name, pic, null, false);
    }

    public static void start(Activity activity, String key, String id, String name, String pic, String mark) {
        start(activity, key, id, name, pic, mark, false);
    }

    public static void start(Activity activity, String key, String id, String name, String pic, String mark, boolean collect) {
        Intent intent = new Intent(activity, VideoActivity.class);
        intent.putExtra("collect", collect);
        intent.putExtra("mark", mark);
        intent.putExtra("name", name);
        intent.putExtra("pic", pic);
        intent.putExtra("key", key);
        intent.putExtra("id", id);
        activity.startActivity(intent);
    }

    private boolean isAutoRotate() {
        return Settings.System.getInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 1;
    }

    private boolean isLand() {
        return ResUtil.isLand(this);
    }

    private boolean isPort() {
        return !isLand();
    }

    public boolean isRotate() {
        return rotate;
    }

    public void setRotate(boolean rotate) {
        this.rotate = rotate;
        if (fullscreen && !rotate) setPadding(mBinding.control.getRoot());
        else noPadding(mBinding.control.getRoot());
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityVideoBinding.inflate(getLayoutInflater());
    }

    @Override
    protected PlaybackService.NavigationCallback getNavigationCallback() {
        return mNavigationCallback;
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
        if (mBinding == null) return;
        bindDanmakuView(mBinding.danmaku);
        adjustLayoutForOrientation();
        onVideoPlaybackReady();
    }

    @Override
    protected void onPrepare() {
        mPlaybackWasReady = false;
        super.onPrepare();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        String oldId = getId();
        super.onNewIntent(intent);
        String id = Objects.toString(intent.getStringExtra("id"), "");
        if (TextUtils.isEmpty(id) || id.equals(oldId)) return;
        mBinding.swipeLayout.setRefreshing(true);
        getIntent().putExtras(intent);
        saveHistory();
        setOrient();
        checkId();
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        super.initView(savedInstanceState);
        ViewCompat.setOnApplyWindowInsetsListener(mBinding.getRoot(), (v, insets) -> setStatusBar(insets));
        mBinding.swipeLayout.setColorSchemeResources(R.color.accent);
        mKeyDown = CustomKeyDown.create(this, mBinding.exo);
        mVideoController = new VideoController(this);
        mFrameParams = mBinding.video.getLayoutParams();
        ResUtil.scalePlayerOverlay(mBinding.videoInfo, mBinding.videoClock);
        mBinding.progressLayout.showProgress();
        mBinding.swipeLayout.setEnabled(false);
        mObserveDetail = new DetailObserver(this);
        mObservePlayer = new PlayerObserver(this);
        mObserveSearch = new SearchObserver(this);
        mBroken = new LinkedHashSet<>();
        mClock = Clock.create();
        mVideoClock = Clock.create(mBinding.videoClock).format("HH:mm");
        mHideControlRunnable = this::hideControl;
        mShowEmptyRunnable = this::showEmpty;
        mSetTrafficRunnable = this::setTraffic;
        mSetOrientRunnable = this::setOrient;
        mCast = new CastController(this, mBinding);
        mCast.createStatus();
        mDanmaku = new DanmakuController(this, mBinding);
        mSpeedScale = new SpeedScaleController(this, mBinding);
        mSkipMarker = new SkipMarkerController(this, this::setR1Callback);
        setOrient();
        mPiP = new PiP();
        mDanmaku.checkImg();
        setRecyclerView();
        setVideoView();
        setViewModel();
        checkId();
        mDanmaku.show();
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    protected void initEvent() {
        mBinding.name.setOnClickListener(view -> onName());
        mBinding.more.setOnClickListener(view -> onMore());
        mBinding.actor.setOnClickListener(view -> onActor());
        mBinding.content.setOnClickListener(view -> onContent());
        mBinding.reverse.setOnClickListener(view -> onReverse());
        mBinding.director.setOnClickListener(view -> onDirector());
        mBinding.name.setOnLongClickListener(view -> onChange());
        mBinding.content.setOnLongClickListener(view -> onCopy());
        mBinding.control.back.setOnClickListener(view -> onBack());
        mBinding.control.cast.setOnClickListener(view -> mCast.onCast());
        mBinding.control.info.setOnClickListener(view -> onInfo());
        mBinding.control.keep.setOnClickListener(view -> onKeep());
        mBinding.control.play.setOnClickListener(view -> checkPlay());
        mBinding.control.next.setOnClickListener(view -> checkNext(true));
        mBinding.control.prev.setOnClickListener(view -> checkPrev());
        mBinding.control.setting.setOnClickListener(view -> onSetting());
        mBinding.control.title.setOnLongClickListener(view -> onChange());
        mBinding.control.right.lock.setOnClickListener(view -> onLock());
        mBinding.control.right.rotate.setOnClickListener(view -> onRotate());
        mBinding.control.danmakuToggle.setOnClickListener(view -> mDanmaku.onDanmakuShow());
        mBinding.control.action.subtitleTracks.setOnClickListener(this::onTrack);
        mBinding.control.action.audio.setOnClickListener(this::onTrack);
        mBinding.control.action.videoTracks.setOnClickListener(this::onTrack);
        mBinding.control.action.loop.setOnClickListener(view -> onLoop());
        mBinding.control.action.scale.setOnClickListener(view -> mSpeedScale.onScale());
        mBinding.control.action.speed.setOnClickListener(view -> mSpeedScale.onSpeed());
        mBinding.control.action.reset.setOnClickListener(view -> onReset());
        mBinding.control.action.actionTitle.setOnClickListener(view -> onTitle());
        mBinding.control.action.player.setOnClickListener(view -> onChoose());
        mBinding.control.action.decode.setOnClickListener(view -> onDecode());
        mBinding.control.action.ending.setOnClickListener(view -> mSkipMarker.onEnding());
        mBinding.control.action.opening.setOnClickListener(view -> mSkipMarker.onOpening());
        mBinding.control.action.danmakuSettings.setOnClickListener(view -> onDanmaku());
        mBinding.control.action.episodes.setOnClickListener(view -> onEpisodes());
        mBinding.control.action.actionInfo.setOnClickListener(view -> onVideoInfo());
        mBinding.control.action.actionInfo.setActivated(PlayerSettings.getVideoInfo() != 3);
        mBinding.control.action.subtitleTracks.setOnLongClickListener(view -> onTextLong());
        mBinding.control.action.speed.setOnLongClickListener(view -> mSpeedScale.onSpeedLong());
        mBinding.control.action.reset.setOnLongClickListener(view -> onResetToggle());
        mBinding.control.action.ending.setOnLongClickListener(view -> mSkipMarker.onEndingReset());
        mBinding.control.action.opening.setOnLongClickListener(view -> mSkipMarker.onOpeningReset());
        mBinding.video.setOnTouchListener((view, event) -> mKeyDown.onTouchEvent(event));
        mBinding.control.action.getRoot().setOnTouchListener(this::onActionTouch);
        mBinding.swipeLayout.setOnRefreshListener(this::onSwipeRefresh);
    }

    private WindowInsetsCompat setStatusBar(WindowInsetsCompat insets) {
        int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
        ViewGroup.LayoutParams lp = mBinding.statusBar.getLayoutParams();
        lp.height = top;
        mBinding.statusBar.setLayoutParams(lp);
        return insets;
    }

    protected void setRecyclerView() {
        mBinding.flag.setHasFixedSize(true);
        mBinding.flag.setItemAnimator(null);
        mBinding.flag.addItemDecoration(new SpaceItemDecoration(R.dimen.vs_8));
        mBinding.flag.setAdapter(mFlagAdapter = new FlagAdapter(this));
        mBinding.quick.setAdapter(mQuickAdapter = new QuickAdapter(this));
        mBinding.episode.setHasFixedSize(true);
        mBinding.episode.setItemAnimator(null);
        mBinding.episode.addItemDecoration(new SpaceItemDecoration(R.dimen.vs_8));
        mBinding.episode.setAdapter(mEpisodeAdapter = new EpisodeAdapter(this, ViewType.HORI));
        mBinding.quality.setHasFixedSize(true);
        mBinding.quality.setItemAnimator(null);
        mBinding.quality.addItemDecoration(new SpaceItemDecoration(R.dimen.vs_8));
        mBinding.quality.setAdapter(mQualityAdapter = new QualityAdapter(this));
        mBinding.control.parse.setHasFixedSize(true);
        mBinding.control.parse.setItemAnimator(null);
        mBinding.control.parse.addItemDecoration(new SpaceItemDecoration(R.dimen.vs_8));
        mBinding.control.parse.setAdapter(mParseAdapter = new ParseAdapter(this, ViewType.DARK));
    }

    private void setVideoView() {
        mBinding.control.action.danmakuSettings.setVisibility(PlayerSettings.isDanmakuLoad() ? View.VISIBLE : View.GONE);
        mBinding.control.action.reset.setText(ResUtil.getStringArray(R.array.select_reset)[AppSettings.getReset()]);
        mBinding.video.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> mPiP.update(this, view));
    }

    private void setVideoView(boolean isInPictureInPictureMode) {
        if (isInPictureInPictureMode) {
            mBinding.video.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        } else {
            mBinding.video.setLayoutParams(mFrameParams);
        }
    }

    @Override
    protected void setDecode() {
        mBinding.control.action.decode.setText(player().getDecodeText());
    }

    @Override
    public void setScale(int scale) {
        if (mHistory != null) mHistory.setScale(scale);
        mBinding.exo.setResizeMode(scale);
        mBinding.control.action.scale.setText(ResUtil.getStringArray(R.array.select_scale)[scale]);
    }

    @Override
    protected void setEpisodeAdapter(List<Episode> items) {
        items = VideoRankManager.applyEpisodes(getHistoryKey(), items);
        mBinding.control.action.episodes.setVisibility(items.size() < 2 ? View.GONE : View.VISIBLE);
        mBinding.control.next.setVisibility(items.size() < 2 ? View.GONE : View.VISIBLE);
        mBinding.control.prev.setVisibility(items.size() < 2 ? View.GONE : View.VISIBLE);
        mBinding.reverse.setVisibility(items.size() < 2 ? View.GONE : View.VISIBLE);
        mBinding.episode.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        mBinding.more.setVisibility(items.size() < 10 ? View.GONE : View.VISIBLE);
        mEpisodeAdapter.addAll(items);
    }

    @Override
    public boolean onLongClick(Episode item) {
        VideoRankDialog.editEpisode(this, getHistoryKey(), getSite().getName(),
                getVideoRankTitle(), item,
                () -> setEpisodeAdapter(new java.util.ArrayList<>(mEpisodeAdapter.getItems())));
        return true;
    }

    @Override
    protected void setQualityVisible(boolean visible) {
        mBinding.qualityText.setVisibility(visible ? View.VISIBLE : View.GONE);
        mBinding.quality.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    @Override
    protected ClickableSpan clickableSpan(Result result) {
        return new ClickableSpan() {
            @Override
            public void onClick(@NonNull View view) {
                FolderActivity.start(getActivity(), getKey(), result);
                ((TextView) view).setMaxLines(Integer.MAX_VALUE);
                setRedirect(true);
            }
        };
    }

    @Override
    protected void setText(Vod item) {
        super.setText(item);
        setText(mBinding.content, 0, item.getContent());
        setOther(mBinding.other, item);
    }

    private void setOther(TextView view, Vod item) {
        StringBuilder sb = new StringBuilder();
        if (!item.getYear().isEmpty()) sb.append(getString(R.string.detail_year, item.getYear())).append("  ");
        if (!item.getArea().isEmpty()) sb.append(getString(R.string.detail_area, item.getArea())).append("  ");
        if (!item.getTypeName().isEmpty()) sb.append(getString(R.string.detail_type, item.getTypeName())).append("  ");
        view.setVisibility(sb.length() == 0 ? View.GONE : View.VISIBLE);
        view.setText(sb.length() > 2 ? sb.substring(0, sb.length() - 2) : sb.toString());
    }

    private void setContentVisible() {
        mBinding.contentLayout.setVisibility(mBinding.content.getVisibility());
    }

    @Override
    protected void setText(TextView view, int resId, String text) {
        super.setText(view, resId, text);
        if (view == mBinding.content) setContentVisible();
    }

    @Override
    protected void getPlayer(Flag flag, Episode episode) {
        int total = flag.getEpisodes().size();
        if (total > 1) {
            mBinding.control.title.setText(getString(R.string.detail_title_total, mBinding.name.getText(), episode.getDisplayName(), total));
        } else {
            mBinding.control.title.setText(getString(R.string.detail_title, mBinding.name.getText(), episode.getDisplayName()));
        }
        super.getPlayer(flag, episode);
        mBinding.control.title.setSelected(true);
        EpisodeDisplayHelper.logIfNeeded(mBinding.name.getText().toString(), episode.getName(), flag.getFlag(), total, episode.getDisplayName());
        updateVideoInfo();
    }

    @Override
    protected void setPlayer(Result result) {
        super.setPlayer(result);
        mBinding.swipeLayout.setRefreshing(false);
        if (result.hasDesc()) setText(mBinding.content, 0, result.getDesc());
    }

    @Override
    public void onItemClick(Flag item) {
        if (item.isActivated()) return;
        mFlagAdapter.setActivated(item);
        scrollToPosition(mBinding.flag, mFlagAdapter.getPosition());
        setEpisodeAdapter(item.getEpisodes());
        setQualityVisible(false);
        seamless(item);
        scrollToPosition(mBinding.episode, mEpisodeAdapter.getPosition());
    }

    @Override
    public void onItemClick(Episode item) {
        App.removeCallbacks(mAutoPlayFirstEpisodeRunnable);
        if (shouldEnterFullscreen(item)) return;
        mFlagAdapter.toggle(item);
        notifyItemChanged(mBinding.episode, mEpisodeAdapter);
        scrollToPosition(mBinding.episode, mEpisodeAdapter.getPosition());
        if (isFullscreen()) Notify.show(getString(R.string.play_ready,
                EpisodeShortName.shorten(item.getName(), getVodName())));
        onRefresh();
    }

    @Override
    protected void reverseEpisode(boolean scroll) {
        super.reverseEpisode(scroll);
        if (scroll) scrollToPosition(mBinding.episode, mEpisodeAdapter.getPosition());
    }

    private void onName() {
        String name = mBinding.name.getText().toString();
        Notify.show(getString(R.string.detail_search, name));
        initSearch(name, false);
    }

    private void onMore() {
        if (mHistory == null) return;
        EpisodeGridDialog.create().reverse(mHistory.isRevSort()).episodes(mEpisodeAdapter.getItems()).show(this);
    }

    private void onActor() {
        mBinding.actor.setMaxLines(mBinding.actor.getMaxLines() == 1 ? Integer.MAX_VALUE : 1);
    }

    private void onDirector() {
        mBinding.director.setMaxLines(mBinding.director.getMaxLines() == 1 ? Integer.MAX_VALUE : 1);
    }

    private void onContent() {
        mBinding.content.setMaxLines(mBinding.content.getMaxLines() == 3 ? Integer.MAX_VALUE : 3);
    }

    private void onReverse() {
        if (mHistory == null) return;
        mHistory.setRevSort(!mHistory.isRevSort());
        reverseEpisode(false);
    }

    private boolean onChange() {
        checkSearch(true);
        return true;
    }

    private boolean onCopy() {
        Util.copy(mBinding.content.getText().toString());
        return true;
    }

    private void onBack() {
        if (isFullscreen()) exitFullscreen();
        else finish();
    }



    @Override public FragmentActivity getOwnerActivity() { return this; }
    @Override public PlayerManager getPlayerManager() { return player(); }
    @Override public String resolveVodName() { return getVodName(); }
    @Override public String resolveKey() { return getKey(); }
    @Override public Flag resolveFlag() { return getFlag(); }
    @Override public Episode resolveEpisode() { return getEpisode(); }
    @Override public History getHistory() { return mHistory; }
    @Override public EpisodeAdapter getEpisodeAdapter() { return mEpisodeAdapter; }
    @Override public Intent getLaunchIntent() { return getIntent(); }

    @Override public boolean isFullscreenMode() { return isFullscreen(); }
    @Override public PlaybackService getService() { return service(); }

    @Override public int getScaleIndex() { return getScale(); }
    @Override public float getKeyDownScale() { return mKeyDown.getScale(); }
    @Override public void resetKeyDownScale() { mKeyDown.resetScale(); }
    @Override public void setR1Callback() { super.setR1Callback(); }
    @Override public void setOpening(long opening) { super.setOpening(opening); }
    @Override public void setEnding(long ending) { super.setEnding(ending); }

    public void onCastDialog() {
        mCast.onCast();
    }

    private void onInfo() {
        InfoDialog.create(this).title(mBinding.control.title.getText()).headers(player().getHeaders()).url(player().getUrl()).show();
    }

    private void onVideoInfo() {
        int currentMode = PlayerSettings.getVideoInfo();
        int newMode = (currentMode + 1) % 4; // Cycle through 0, 1, 2, 3
        PlayerSettings.putVideoInfo(newMode);
        mBinding.control.action.actionInfo.setActivated(newMode != 3);
        updateVideoInfo();
    }

    private void updateVideoInfo() {
        String traffic = mBinding.progress.traffic.getText().toString();
        String info = mBufferingStatus.isEmpty()
                ? traffic
                : TextUtils.isEmpty(traffic) ? mBufferingStatus : traffic + " | " + mBufferingStatus;
        renderPlaybackOverlay(info);
        Flag flag = getFlag();
        Episode episode = getEpisode();
        if (flag != null) {
            int total = flag.getEpisodes().size();
            String episodeInfo = EpisodeDisplayHelper.buildDisplayText(mBinding.name.getText(), episode.getDisplayName(), total);
            CharSequence existing = mBinding.videoInfo.getText();
            if (existing.length() > 0) {
                mBinding.videoInfo.setText(existing + " | " + episodeInfo);
            } else {
                mBinding.videoInfo.setText(episodeInfo);
                mBinding.videoInfo.setVisibility(PlaybackOverlayState.shouldShowVideoInfo(PlayerSettings.getVideoInfo(), isFullscreen()) ? View.VISIBLE : View.GONE);
            }
        }
    }

    private void onKeep() {
        toggleKeep();
    }

    private void checkPlay() {
        setR1Callback();
        if (player().isPlaying()) onPaused();
        else if (player().isEmpty()) onRefresh();
        else onPlay();
    }

    private void onSetting() {
        ControlDialog.create().parent(mBinding).history(mHistory).parse(isUseParse()).player(player()).show(this);
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

    private void onTitle() {
        hideControl();
    }

    private boolean onResetToggle() {
        AppSettings.putReset(Math.abs(AppSettings.getReset() - 1));
        mBinding.control.action.reset.setText(ResUtil.getStringArray(R.array.select_reset)[AppSettings.getReset()]);
        return true;
    }

    private void onDecode() {
        mClock.setCallback(null);
        player().toggleDecode();
        setR1Callback();
        setDecode();
    }

    private void onEpisodes() {
        EpisodeListDialog.create(this).episodes(mEpisodeAdapter.getItems()).show();
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
        setR1Callback();
        return false;
    }

    private void onSwipeRefresh() {
        if (mBinding.progressLayout.isEmpty()) getDetail();
        else onRefresh();
    }

    @Override
    protected void enterFullscreen() {
        if (isFullscreen()) return;
        setFullscreen(true);
        updateVideoInfo();
        setTransition();
        mBinding.shadow.setVisibility(View.GONE);
        mBinding.swipeLayout.setVisibility(View.GONE);
        mBinding.video.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        mBinding.control.title.setVisibility(View.VISIBLE);
        mKeyDown.resetScale();
        mDanmaku.setSize();
        hideControl();
    }

    @Override
    protected void exitFullscreen() {
        if (!isFullscreen()) return;
        setFullscreen(false);
        updateVideoInfo();
        setTransition();
        mBinding.shadow.setVisibility(View.VISIBLE);
        mBinding.swipeLayout.setVisibility(View.VISIBLE);
        mBinding.video.setLayoutParams(mFrameParams);
        adjustLayoutForOrientation();
        mBinding.control.title.setVisibility(View.INVISIBLE);
        postUi(() -> scrollToPosition(mBinding.episode, mEpisodeAdapter.getPosition()), 100);
        mKeyDown.resetScale();
        setRotate(false);
        mDanmaku.setSize();
        hideControl();
    }

    @Override
    protected void setDanmakuSize() {
        mDanmaku.setSize();
    }

    private void setTransition() {
        ChangeBounds transition = new ChangeBounds();
        transition.setDuration(150);
        ViewGroup parent = (ViewGroup) mBinding.video.getParent();
        TransitionManager.beginDelayedTransition(parent, transition);
    }

    private int getLockOrient() {
        if (isLock()) {
            return ResUtil.getScreenOrientation(this);
        } else if (isRotate()) {
            return ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT;
        } else if (isPort() && isAutoRotate()) {
            return ActivityInfo.SCREEN_ORIENTATION_FULL_USER;
        } else {
            return isLand() ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT;
        }
    }

    @Override
    protected void showProgress() {
        mBinding.progress.getRoot().setVisibility(View.VISIBLE);
        mBufferingCountdown.start(SystemClock.elapsedRealtime());
        updateBufferingStatus();
        updateVideoInfo();
        if (uiStarted) App.post(mSetTrafficRunnable, 0);
        hideError();
    }

    @Override
    protected void hideProgress() {
        mBinding.progress.getRoot().setVisibility(View.GONE);
        mBufferingStatus = "";
        mBufferingCountdown.reset();
        App.removeCallbacks(mSetTrafficRunnable);
        Traffic.reset();
        updateVideoInfo();
    }

    @Override
    protected void onStateChanged(int state) {
        super.onStateChanged(state);
        if (state == androidx.media3.common.Player.STATE_READY) mPlaybackWasReady = true;
    }

    @Override
    protected void showError(String text) {
        mBinding.widget.error.setVisibility(View.VISIBLE);
        mBinding.widget.error.setText(text);
        hideProgress();
    }

    @Override
    protected void hideError() {
        mBinding.widget.error.setVisibility(View.GONE);
        mBinding.widget.error.setText("");
    }



    @Override
    protected void showControl() {
        if (service() == null || isInPictureInPictureMode()) return;
        mBinding.control.danmakuToggle.setVisibility(isLock() || !player().haveDanmaku() ? View.GONE : View.VISIBLE);
        mBinding.control.setting.setVisibility(mHistory == null || isFullscreen() ? View.GONE : View.VISIBLE);
        mBinding.control.right.rotate.setVisibility(isFullscreen() && !isLock() ? View.VISIBLE : View.GONE);
        mBinding.control.keep.setVisibility(mHistory == null || isFullscreen() ? View.GONE : View.VISIBLE);
        mBinding.control.parse.setVisibility(isFullscreen() && isUseParse() ? View.VISIBLE : View.GONE);
        mBinding.control.action.getRoot().setVisibility(isFullscreen() ? View.VISIBLE : View.GONE);
        mBinding.control.right.lock.setVisibility(isFullscreen() ? View.VISIBLE : View.GONE);
        mBinding.control.info.setVisibility(player().isEmpty() ? View.GONE : View.VISIBLE);
        mBinding.control.cast.setVisibility(player().isEmpty() ? View.GONE : View.VISIBLE);
        mCast.checkCastImg();
        mBinding.control.center.setVisibility(isLock() ? View.GONE : View.VISIBLE);
        mBinding.control.bottom.setVisibility(isLock() ? View.GONE : View.VISIBLE);
        mBinding.control.back.setVisibility(isLock() ? View.GONE : View.VISIBLE);
        mBinding.control.top.setVisibility(isLock() ? View.GONE : View.VISIBLE);
        mBinding.control.getRoot().setVisibility(View.VISIBLE);
        setR1Callback();
    }

    @Override
    protected void hideControl() {
        mBinding.control.getRoot().setVisibility(View.GONE);
        App.removeCallbacks(mHideControlRunnable);
    }

    private void hideSheet() {
        getSupportFragmentManager().getFragments().stream().filter(fragment -> fragment instanceof BottomSheetDialogFragment).map(fragment -> (BottomSheetDialogFragment) fragment).forEach(BottomSheetDialogFragment::dismiss);
    }

    @Override
    protected void setTraffic() {
        if (!uiStarted) return;
        super.setTraffic();
        updateBufferingStatus();
        if (PlayerSettings.showVideoInfo() || PlayerSettings.showVideoClock()) updateVideoInfo();
        App.post(mSetTrafficRunnable, 1000);
    }

    private void updateBufferingStatus() {
        if (mBinding == null || mBinding.progress.getRoot().getVisibility() != View.VISIBLE) return;
        long bufferedDuration = 0L;
        PlayerManager manager = player();
        androidx.media3.common.Player current = manager == null ? null : manager.getPlayer();
        if (current != null && current.isCommandAvailable(
                androidx.media3.common.Player.COMMAND_GET_CURRENT_MEDIA_ITEM)) {
            bufferedDuration = Math.max(0L,
                    current.getBufferedPosition() - current.getCurrentPosition());
        }
        long target = mPlaybackWasReady
                ? BufferingCountdown.REBUFFER_TARGET_MILLIS
                : BufferingCountdown.INITIAL_TARGET_MILLIS;
        BufferingCountdown.Status status = mBufferingCountdown.update(
                SystemClock.elapsedRealtime(), bufferedDuration, target);
        mBufferingStatus = getString(R.string.buffer_status_eta, status.etaSeconds());
    }

    private void setOrient() {
        // Disabled: no automatic orientation changes
    }

    @Override
    protected void onOrientationChanged(Configuration newConfig) {
        super.onOrientationChanged(newConfig);
        if (!isFullscreen()) {
            adjustLayoutForOrientation();
        }
        setOrient();
    }

    private void adjustLayoutForOrientation() {
        if (mBinding == null) return;
        LinearLayout contentRow = mBinding.contentRow;
        FrameLayout video = mBinding.video;
        View shadow = mBinding.shadow;
        SwipeRefreshLayout swipeLayout = mBinding.swipeLayout;
        if (contentRow == null || video == null || shadow == null || swipeLayout == null) return;
        if (isLand()) {
            contentRow.setOrientation(LinearLayout.HORIZONTAL);
            video.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT,
                    LayoutConfig.MOBILE_VIDEO_LANDSCAPE_PLAYER_WEIGHT));
            shadow.setLayoutParams(new LinearLayout.LayoutParams(
                    ResUtil.getDimensionPixelSize(R.dimen.vs_2),
                    LinearLayout.LayoutParams.MATCH_PARENT));
            swipeLayout.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT,
                    LayoutConfig.MOBILE_VIDEO_LANDSCAPE_DETAIL_WEIGHT));
        } else {
            contentRow.setOrientation(LinearLayout.VERTICAL);
            video.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    ResUtil.getDimensionPixelSize(R.dimen.vs_220)));
            shadow.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    ResUtil.getDimensionPixelSize(R.dimen.vs_4)));
            swipeLayout.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0,
                    LayoutConfig.MOBILE_VIDEO_PORTRAIT_DETAIL_WEIGHT));
        }
        mFrameParams = video.getLayoutParams();
    }



    @Override
    protected void setKeepImage(boolean kept) {
        mBinding.control.keep.setImageResource(kept ? R.drawable.ic_control_keep_on : R.drawable.ic_control_keep_off);
    }

    private void checkLockImg() {
        mBinding.control.right.lock.setImageResource(isLock() ? R.drawable.ic_control_lock_on : R.drawable.ic_control_lock_off);
    }





    @Override
    public void onCastSessionChanged(ProjectionManager.CastSessionState state) {
        mCast.onSessionChanged(state);
    }

    @Override
    protected void notifyItemChanged(RecyclerView view, RecyclerView.Adapter<?> adapter) {
        UiSafe.notifyItemRangeChanged(view, adapter);
    }

    private void scrollToPosition(RecyclerView view, int position) {
        UiSafe.scrollToPosition(view, position);
    }

    @Override
    protected void showEmpty() {
        showError(getString(R.string.error_detail));
        mBinding.swipeLayout.setEnabled(true);
        mBinding.progressLayout.showEmpty();
    }

    @Override
    protected void setDetail(Result result) {
        mBinding.swipeLayout.setRefreshing(false);
        super.setDetail(result);
    }

    @Override
    protected void getDetail(Vod item) {
        mBinding.swipeLayout.setRefreshing(true);
        mBinding.swipeLayout.setEnabled(false);
        super.getDetail(item);
    }

    @Override
    protected void onPlayingChanged(boolean isPlaying) {
        updateVideoNetworkWarning(isPlaying);
        if (isPlaying) {
            mPiP.update(this, true);
            mBinding.control.play.setImageResource(androidx.media3.ui.R.drawable.exo_icon_pause);
        } else if (isPaused()) {
            mPiP.update(this, false);
            mBinding.control.play.setImageResource(androidx.media3.ui.R.drawable.exo_icon_play);
        }
    }

    @Override
    protected void onSizeChanged(VideoSize size) {
        changeHeight();
        updateVideoInfo();
    }

    @Override
    protected void onTracksChanged() {
        mCast.onTracksChanged();
    }

    @Override
    public void onSubtitleClick() {
        SubtitleDialog.create().view(mBinding.exo.getSubtitleView()).full(isFullscreen()).show(this);
        hideControl();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onCastEvent(CastEvent event) {
        if (isRedirect()) return;
        ReceiveDialog.create().event(event).show(this);
    }

    private void checkOrientation() {
        if (isFullscreen() && !isRotate() && player().isPortrait()) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT);
            setRotate(true);
        } else if (isFullscreen() && isRotate() && player().isLandscape()) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE);
            setRotate(false);
        }
    }

    private void changeHeight() {
        if (isLand() || isFullscreen() || isInPictureInPictureMode()) return;
        int videoWidth = player().getVideoWidth();
        int videoHeight = player().getVideoHeight();
        if (videoWidth == 0 || videoHeight == 0) return;
        int viewWidth = ResUtil.getScreenWidth();
        int minHeight = ResUtil.getDimensionPixelSize(R.dimen.vs_150);
        int maxHeight = ResUtil.getScreenHeight() / 2;
        int calculated = (int) (viewWidth * ((float) videoHeight / videoWidth));
        int finalHeight = Math.max(minHeight, Math.min(maxHeight, calculated));
        if (finalHeight == mBinding.video.getHeight()) return;
        if (mAnimator != null && mAnimator.isRunning()) mAnimator.cancel();
        mAnimator = mVideoController.createTransitionAnimator(mBinding.video, mBinding.video.getHeight(), finalHeight, 300);
        mAnimator.start();
    }

    protected void checkControl() {
        if (isVisible(mBinding.control.getRoot())) showControl();
    }

    @Override
    public void onScale(int tag) {
        mSpeedScale.onScale(tag);
    }

    @Override
    public void onParse(Parse item) {
        onItemClick(item);
    }

    @Override
    public void onSpeedUp() {
        mSpeedScale.onSpeedUp();
    }

    @Override
    public void onSpeedEnd() {
        mSpeedScale.onSpeedEnd();
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
        if (ProjectionManager.get().getConnectedDevice() != null) {
            ProjectionManager.get().castNext();
            return;
        }
        if (mEpisodeAdapter.getItemCount() == 1) onRefresh();
        else checkNext(true);
    }

    @Override
    public void onFlingDown() {
        if (ProjectionManager.get().getConnectedDevice() != null) {
            ProjectionManager.get().castPrev();
            return;
        }
        if (mEpisodeAdapter.getItemCount() == 1) onRefresh();
        else checkPrev();
    }

    @Override
    public long getVideoDuration() {
        return player().getDuration();
    }

    @Override
    public void onSeeking(long time) {
        mBinding.widget.overlayAction.setImageResource(time > 0 ? R.drawable.ic_widget_forward : R.drawable.ic_widget_rewind);
        mBinding.widget.time.setText(player().getPositionTime(time));
        mBinding.widget.overlaySeek.setVisibility(View.VISIBLE);
        hideProgress();
    }

    @Override
    public void onSeekEnd(long time) {
        if (ProjectionManager.get().getConnectedDevice() != null) {
            long current = ProjectionManager.get().getCastPosition();
            if (current < 0) {
                if (ProjectionManager.get().isCompatCastActive()) {
                    Notify.show(R.string.cast_compat_no_seek);
                    return;
                }
                PlayerManager player = player();
                current = player != null ? player.getPosition() : 0;
            }
            ProjectionManager.get().seek(current + time);
            return;
        }
        seekTo(time);
    }

    @Override
    public void onSingleTap() {
        if (isVisible(mBinding.control.getRoot())) hideControl();
        else showControl();
    }

    @Override
    public void onDoubleTap() {
        if (isLock()) return;
        if (!isFullscreen()) {
            enterFullscreen();
        } else if (player().isPlaying()) {
            showControl();
            onPaused();
        } else {
            hideControl();
            onPlay();
        }
    }

    @Override
    public void onDoubleTapSeek(long time) {
        if (isLock() || !isFullscreen()) {
            onDoubleTap();
            return;
        }
        if (ProjectionManager.get().getConnectedDevice() != null) {
            long current = ProjectionManager.get().getCastPosition();
            if (current < 0) {
                if (ProjectionManager.get().isCompatCastActive()) {
                    Notify.show(R.string.cast_compat_no_seek);
                    return;
                }
                PlayerManager player = player();
                current = player != null ? player.getPosition() : 0;
            }
            ProjectionManager.get().seek(current + time);
            return;
        }
        seekTo(time);
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
        PlayerManager player = player();
        if (player != null && mPiP != null && player.haveTrack(C.TRACK_TYPE_VIDEO)) {
            mPiP.enter(this, player.getVideoWidth(), player.getVideoHeight(), getScale());
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, @NonNull Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        if (!isFullscreen()) setVideoView(isInPictureInPictureMode);
        if (isInPictureInPictureMode) {
            hideControl();
            mDanmaku.hide();
            hideSheet();
        } else {
            mDanmaku.show();
            if (isStop()) finish();
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (!isFullscreen()) {
            adjustLayoutForOrientation();
            setOrient();
        }
        if (isFullscreen()) Util.hideSystemUI(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (isFullscreen() && hasFocus) Util.hideSystemUI(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        uiStarted = true;
        ProjectionManager.get().addCastSessionListener(this);
        mClock.start();
        mVideoClock.start();
        if (isVisible(mBinding.progress.getRoot())) App.post(mSetTrafficRunnable, 0);
        setAudioOnly(false);
        setStop(false);
    }

    @Override
    protected void onStop() {
        uiStarted = false;
        App.removeCallbacks(mSetTrafficRunnable);
        ProjectionManager.get().removeCastSessionListener(this);
        super.onStop();
        if (PlayerSettings.isBackgroundOff()) mClock.stop();
        mVideoClock.stop();
        if (!isAudioOnly()) setStop(true);
    }

    @Override
    protected void onBackInvoked() {
        if (isVisible(mBinding.control.getRoot())) {
            hideControl();
        } else if (isFullscreen() && !isLock()) {
            exitFullscreen();
        } else if (!isLock()) {
            mViewModel.stopSearch();
            startHomeAsRoot();
            super.onBackInvoked();
        }
    }

    @Override
    protected void onDestroy() {
        Timer.get().reset();
        App.removeCallbacks(mSetTrafficRunnable, mSetOrientRunnable);
        if (mAnimator != null) {
            mAnimator.removeAllUpdateListeners();
            mAnimator.cancel();
            mAnimator = null;
        }
        mKeyDown = null;
        mVideoController = null;
        mCast.release();
        mPiP = null;
        mSetTrafficRunnable = null;
        mSetOrientRunnable = null;
        super.onDestroy();
    }
}
