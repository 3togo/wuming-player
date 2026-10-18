package com.wuming.android.tv.ui.activity;
import com.wuming.android.tv.setting.PlayerSettings;
import com.wuming.android.tv.setting.AppSettings;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.style.ClickableSpan;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.leanback.widget.OnChildViewHolderSelectedListener;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.wuming.android.tv.App;
import com.wuming.android.tv.R;
import com.wuming.android.tv.api.config.VodConfig;
import com.wuming.android.tv.bean.Config;
import com.wuming.android.tv.bean.Episode;
import com.wuming.android.tv.bean.EpisodeShortName;
import com.wuming.android.tv.bean.VodShortName;
import com.wuming.android.tv.bean.Flag;
import com.wuming.android.tv.bean.History;
import com.wuming.tv.core.library.EpisodeAdvancePolicy;
import com.wuming.android.tv.bean.Result;
import com.wuming.android.tv.bean.Track;
import com.wuming.android.tv.bean.Vod;
import com.wuming.android.tv.databinding.ActivityVideoBinding;
import com.wuming.android.tv.event.CastEvent;
import com.wuming.android.tv.impl.Callback;
import com.wuming.android.tv.player.PlayerHelper;
import com.wuming.android.tv.player.PlayerManager;
import com.wuming.android.tv.library.PlaylistEpisodeSelection;
import com.wuming.android.tv.library.PlaylistPlaybackPolicy;
import com.wuming.android.tv.library.PlaylistStore;
import com.wuming.android.tv.service.PlaybackService;
import com.wuming.android.tv.ui.adapter.ArrayAdapter;
import com.wuming.android.tv.ui.adapter.EpisodeAdapter;
import com.wuming.android.tv.ui.adapter.FlagAdapter;
import com.wuming.android.tv.ui.adapter.ParseAdapter;
import com.wuming.android.tv.ui.adapter.PartAdapter;
import com.wuming.android.tv.ui.adapter.QualityAdapter;
import com.wuming.android.tv.ui.adapter.QuickAdapter;
import com.wuming.android.tv.ui.base.BaseVideoActivity;
import com.wuming.android.tv.ui.base.PlaybackOverlayState;
import com.wuming.android.tv.ui.custom.CustomKeyDownVod;
import com.wuming.android.tv.ui.custom.CustomSeekView;
import com.wuming.android.tv.ui.dialog.ContentDialog;
import com.wuming.android.tv.ui.dialog.EpisodeGridDialog;
import com.wuming.android.tv.ui.dialog.InfoDialog;
import com.wuming.android.tv.ui.dialog.VideoRankDialog;
import com.wuming.android.tv.ui.dialog.ReceiveDialog;
import com.wuming.android.tv.ui.dialog.SubtitleDialog;
import com.wuming.android.tv.ui.dialog.TimerDialog;
import com.wuming.android.tv.ui.dialog.TrackDialog;
import com.wuming.android.tv.utils.Clock;
import com.wuming.android.tv.utils.KeyUtil;
import com.wuming.android.tv.utils.TvRemoteKeys;
import com.wuming.android.tv.utils.TvRemoteMappings;
import com.wuming.android.tv.utils.Notify;
import com.wuming.android.tv.utils.PartUtil;
import com.wuming.android.tv.utils.ResUtil;
import com.wuming.android.tv.utils.EpisodeDisplayHelper;
import com.wuming.android.tv.utils.VideoRankManager;
import com.wuming.android.tv.utils.Timer;
import com.wuming.android.tv.utils.Traffic;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public class VideoActivity extends BaseVideoActivity implements CustomKeyDownVod.Listener, TrackDialog.Listener, InfoDialog.Listener, ArrayAdapter.OnClickListener, FlagAdapter.OnClickListener, EpisodeAdapter.OnClickListener, QualityAdapter.OnClickListener, QuickAdapter.OnClickListener, ParseAdapter.OnClickListener, SkipMarkerController.Host, SpeedScaleController.Host, CastController.Host {

    private final VideoFocusHelper mFocus = new VideoFocusHelper(this::findViewById);

    private ArrayAdapter mArrayAdapter;
    private PartAdapter mPartAdapter;
    private CustomKeyDownVod mKeyDown;
    private SkipMarkerController mSkipMarker;
    private SpeedScaleController mSpeedScale;
    private CastController mCast;
    private Runnable mUpdateFocusRunnable;
    private Runnable mSetTrafficRunnable;
    private View mFocus1;
    private View mFocus2;

    public static void cast(Activity activity, History history) {
        cast(activity, history, null);
    }

    public static void cast(Activity activity, History history, String audioTrackFormat) {
        start(activity, history.getSiteKey(), history.getVodId(), history.getVodName(), history.getVodPic(), null, false, true, audioTrackFormat);
    }

    public static void collect(Activity activity, String key, String id, String name, String pic) {
        start(activity, key, id, name, pic, null, true, false);
    }

    public static void start(Activity activity, String key, String id, String name, String pic) {
        start(activity, key, id, name, pic, null, false, false);
    }

    public static void start(Activity activity, String key, String id, String name, String pic, String mark) {
        start(activity, key, id, name, pic, mark, false, false);
    }

    public static void start(Activity activity, String key, String id, String name, String pic, String mark, boolean collect) {
        start(activity, key, id, name, pic, mark, collect, false);
    }

    public static void start(Activity activity, String key, String id, String name, String pic, String mark, boolean collect, boolean cast) {
        start(activity, key, id, name, pic, mark, collect, cast, null);
    }

    public static void start(Activity activity, String key, String id, String name, String pic, String mark, boolean collect, boolean cast, String audioTrackFormat) {
        activity.startActivity(createIntent(activity, key, id, name, pic, mark, collect, cast, audioTrackFormat));
    }

    public static void startPlaylist(Activity activity, History item) {
        Intent intent = createIntent(activity, item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic(), null, false, false, null);
        intent.putExtra("playlist", true);
        intent.putExtra("playlistKey", item.getKey());
        activity.startActivity(intent);
    }

    private static Intent createIntent(Activity activity, String key, String id, String name, String pic, String mark, boolean collect, boolean cast, String audioTrackFormat) {
        Intent intent = new Intent(activity, VideoActivity.class);
        intent.putExtra("collect", collect);
        intent.putExtra("cast", cast);
        intent.putExtra("mark", mark);
        intent.putExtra("name", name);
        intent.putExtra("pic", pic);
        intent.putExtra("key", key);
        intent.putExtra("id", id);
        intent.putExtra("playlist", false);
        if (audioTrackFormat != null) intent.putExtra("audioTrackFormat", audioTrackFormat);
        return intent;
    }

    private boolean isCast() {
        return getIntent().getBooleanExtra("cast", false);
    }

    private String getCastAudioTrackFormat() {
        return getIntent().getStringExtra("audioTrackFormat");
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
        bindDanmakuView(mBinding.danmaku);
        setDanmakuSize();
        onVideoPlaybackReady();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        String oldId = getId();
        super.onNewIntent(intent);
        String id = Objects.toString(intent.getStringExtra("id"), "");
        if (TextUtils.isEmpty(id) || id.equals(oldId)) return;
        getIntent().putExtras(intent);
        saveHistory();
        checkId();
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        super.initView(savedInstanceState);
        mFrameParams = mBinding.video.getLayoutParams();
        ResUtil.scalePlayerOverlay(mBinding.videoInfo, mBinding.videoClock);
        mClock = Clock.create(mBinding.widget.clock);
        mVideoClock = Clock.create(mBinding.videoClock).format("HH:mm");
        mKeyDown = CustomKeyDownVod.create(this);
        mSkipMarker = new SkipMarkerController(this);
        mSpeedScale = new SpeedScaleController(this, mBinding);
        mCast = new CastController(this, mBinding);
        mObserveDetail = new DetailObserver(this);
        mObservePlayer = new PlayerObserver(this);
        mObserveSearch = new SearchObserver(this);
        mBroken = new LinkedHashSet<>();
        mHideControlRunnable = this::hideControl;
        mUpdateFocusRunnable = this::updateFocus;
        mSetTrafficRunnable = this::setTraffic;
        mShowEmptyRunnable = this::showEmpty;
        setRecyclerView();
        setVideoView();
        setViewModel();
        checkId();
        checkCast();
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    protected void initEvent() {
        mBinding.keep.setOnClickListener(view -> onKeep());
        mBinding.video.setOnClickListener(view -> onVideo());
        mBinding.change1.setOnClickListener(view -> onChange());
        mBinding.bilibiliLogin.setOnClickListener(view -> onBilibiliLogin());
        mBinding.content.setOnClickListener(view -> onContent());
        mBinding.control.action.subtitleTracks.setOnClickListener(this::onTrack);
        mBinding.control.action.audio.setOnClickListener(this::onTrack);
        mBinding.control.action.videoTracks.setOnClickListener(this::onTrack);
        mBinding.control.action.speed.setUpListener(mSpeedScale::onSpeedAdd);
        mBinding.control.action.speed.setDownListener(mSpeedScale::onSpeedSub);
        mBinding.control.action.ending.setUpListener(mSkipMarker::onEndingAdd);
        mBinding.control.action.ending.setDownListener(mSkipMarker::onEndingSub);
        mBinding.control.action.opening.setUpListener(mSkipMarker::onOpeningAdd);
        mBinding.control.action.opening.setDownListener(mSkipMarker::onOpeningSub);
        mBinding.control.action.subtitleTracks.setUpListener(this::onSubtitleClick);
        mBinding.control.action.subtitleTracks.setDownListener(this::onSubtitleClick);
        mBinding.control.action.loop.setOnClickListener(view -> onLoop());
        mBinding.control.action.timer.setOnClickListener(view -> TimerDialog.create().show(this));
        mBinding.control.action.streamTools.setOnClickListener(view -> onInfo());
        mBinding.control.action.actionInfo.setOnClickListener(view -> onVideoInfo());
        mBinding.control.action.actionInfo.setOnLongClickListener(view -> { onInfo(); return true; });
        mBinding.control.action.actionInfo.setActivated(PlayerSettings.getVideoInfo() != 3);
        mBinding.control.action.next.setOnClickListener(view -> checkNext(true));
        mBinding.control.action.prev.setOnClickListener(view -> checkPrev());
        mBinding.control.action.scale.setOnClickListener(view -> mSpeedScale.onScale());
        mBinding.control.action.speed.setOnClickListener(view -> mSpeedScale.onSpeed());
        mBinding.control.action.reset.setOnClickListener(view -> onReset());
        mBinding.control.action.actionTitle.setOnClickListener(view -> onTitle());
        mBinding.control.action.player.setOnClickListener(view -> onChoose());
        mBinding.control.action.decode.setOnClickListener(view -> onDecode());
        mBinding.control.action.ending.setOnClickListener(view -> mSkipMarker.onEnding());
        mBinding.control.action.change2.setOnClickListener(view -> onChange());
        mBinding.control.action.danmakuSettings.setOnClickListener(view -> onDanmaku());
        mBinding.control.action.cast.setOnClickListener(view -> mCast.onCast());
        mBinding.control.action.episodes.setOnClickListener(view -> onEpisodes());
        mBinding.control.action.reverse.setOnClickListener(view -> onReverse());
        mBinding.control.action.opening.setOnClickListener(view -> mSkipMarker.onOpening());
        mBinding.control.action.speed.setOnLongClickListener(view -> mSpeedScale.onSpeedLong());
        mBinding.control.action.reset.setOnLongClickListener(view -> onResetToggle());
        mBinding.control.action.ending.setOnLongClickListener(view -> mSkipMarker.onEndingReset());
        mBinding.control.action.opening.setOnLongClickListener(view -> mSkipMarker.onOpeningReset());
        mBinding.episodeReverse.setOnClickListener(view -> onReverse());
        mBinding.episodeRank.setOnClickListener(view -> onRank());
        mBinding.episodeMore.setOnClickListener(view -> onMore());
        mBinding.video.setOnTouchListener((view, event) -> mKeyDown.onTouchEvent(event));
        mBinding.episode.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                if (child != null && mBinding.video != mFocus1) mFocus1 = child.itemView;
            }
        });
        mBinding.array.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                if (mEpisodeAdapter.getItemCount() > 20 && position > 1) {
                    int target = (position - 2) * 20;
                    mBinding.episode.setSelectedPosition(Math.min(target, mEpisodeAdapter.getItemCount() - 1));
                }
            }
        });
    }

    protected void setRecyclerView() {
        mBinding.flag.setHorizontalSpacing(ResUtil.getDimensionPixelSize(R.dimen.vs_8));
        mBinding.flag.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.flag.setAdapter(mFlagAdapter = new FlagAdapter(this));
        mBinding.episode.setHorizontalSpacing(ResUtil.getDimensionPixelSize(R.dimen.vs_8));
        mBinding.episode.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.episode.setAdapter(mEpisodeAdapter = new EpisodeAdapter(this));
        mBinding.quality.setHorizontalSpacing(ResUtil.getDimensionPixelSize(R.dimen.vs_8));
        mBinding.quality.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.quality.setAdapter(mQualityAdapter = new QualityAdapter(this));
        mBinding.array.setHorizontalSpacing(ResUtil.getDimensionPixelSize(R.dimen.vs_8));
        mBinding.array.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.array.setAdapter(mArrayAdapter = new ArrayAdapter(this));
        mBinding.part.setHorizontalSpacing(ResUtil.getDimensionPixelSize(R.dimen.vs_8));
        mBinding.part.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.part.setAdapter(mPartAdapter = new PartAdapter(item -> initSearch(item, false)));
        mBinding.quick.setHorizontalSpacing(ResUtil.getDimensionPixelSize(R.dimen.vs_8));
        mBinding.quick.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.quick.setAdapter(mQuickAdapter = new QuickAdapter(this));
        mBinding.control.parse.setHorizontalSpacing(ResUtil.getDimensionPixelSize(R.dimen.vs_8));
        mBinding.control.parse.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.control.parse.setAdapter(mParseAdapter = new ParseAdapter(this));
        mParseAdapter.addAll(VodConfig.get().getParses());
    }

    private void setVideoView() {
        mBinding.control.action.danmakuSettings.setVisibility(PlayerSettings.isDanmakuLoad() ? View.VISIBLE : View.GONE);
        mBinding.control.action.reset.setText(ResUtil.getStringArray(R.array.select_reset)[AppSettings.getReset()]);
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
        PlaylistEpisodeSelection selection = playlistSelection();
        if (selection != null && getFlag() != null) {
            items = PlaylistPlaybackPolicy.filter(selection, getFlag().getFlag(), items);
        }
        boolean hasEpisodes = !items.isEmpty();
        mBinding.episode.setVisibility(hasEpisodes ? View.VISIBLE : View.GONE);
        mBinding.episodeHeader.setVisibility(hasEpisodes ? View.VISIBLE : View.GONE);
        mBinding.episodeReverse.setVisibility(items.size() > 1 ? View.VISIBLE : View.GONE);
        mBinding.episodeMore.setVisibility(items.size() > 10 ? View.VISIBLE : View.GONE);
        mEpisodeAdapter.addAll(items);
        setArrayAdapter(items.size());
        setR2Callback();
    }

    @Override
    public boolean onLongClick(Episode item) {
        showRank(item);
        return true;
    }

    private void onRank() {
        if (mEpisodeAdapter == null || mEpisodeAdapter.isEmpty()) return;
        int position = mBinding.episode.getSelectedPosition();
        if (position < 0 || position >= mEpisodeAdapter.getItemCount()) position = mEpisodeAdapter.getPosition();
        if (position < 0 || position >= mEpisodeAdapter.getItemCount()) position = 0;
        showRank(mEpisodeAdapter.get(position));
    }

    private void showRank(Episode item) {
        VideoRankDialog.editEpisode(this, getHistoryKey(), getSite().getName(),
                getVideoRankTitle(), item,
                () -> setEpisodeAdapter(new java.util.ArrayList<>(mEpisodeAdapter.getItems())));
    }

    @Override
    protected void setQualityVisible(boolean visible) {
        mBinding.quality.setVisibility(visible ? View.VISIBLE : View.GONE);
        setR2Callback();
    }

    @Override
    protected ClickableSpan clickableSpan(Result result) {
        return new ClickableSpan() {
            @Override
            public void onClick(@NonNull View view) {
                VodActivity.start(getActivity(), getKey(), result);
                setRedirect(true);
            }
        };
    }

    @Override
    protected void setText(Vod item) {
        mBinding.content.setTag(item.getContent());
        boolean bilibili = isBilibiliSite();
        mBinding.bilibiliLogin.setVisibility(bilibili ? View.VISIBLE : View.GONE);
        setText(mBinding.year, R.string.detail_year, item.getYear());
        setText(mBinding.area, R.string.detail_area, item.getArea());
        setText(mBinding.type, R.string.detail_type, item.getTypeName());
        super.setText(item);
    }

    @Override
    protected void getPlayer(Flag flag, Episode episode) {
        int total = flag.getEpisodes().size();
        if (total > 1) {
            mBinding.widget.overlayTitle.setText(getString(R.string.detail_title_total, mBinding.name.getText(), episode.getDisplayName(), total));
        } else {
            mBinding.widget.overlayTitle.setText(getString(R.string.detail_title, mBinding.name.getText(), episode.getDisplayName()));
        }
        super.getPlayer(flag, episode);
        mBinding.widget.overlayTitle.setSelected(true);
        EpisodeDisplayHelper.logIfNeeded(mBinding.name.getText().toString(), episode.getName(), flag.getFlag(), total, episode.getDisplayName());
        updateVideoInfo();
    }

    @Override
    protected void setPlayer(Result result) {
        super.setPlayer(result);
        if (result.hasDesc()) mBinding.content.setTag(result.getDesc());
    }

    @Override
    public void onItemClick(Flag item) {
        if (mFlagAdapter.getItemCount() == 0 || item.isActivated()) return;
        mFlagAdapter.setActivated(item);
        mBinding.flag.setSelectedPosition(mFlagAdapter.indexOf(item));
        setEpisodeAdapter(item.getEpisodes());
        setQualityVisible(false);
        seamless(item);
    }

    @Override
    public void onItemClick(Episode item) {
        App.removeCallbacks(mAutoPlayFirstEpisodeRunnable);
        if (shouldEnterFullscreen(item)) return;
        mFlagAdapter.toggle(item);
        notifyItemChanged(mBinding.episode, mEpisodeAdapter);
        int pos = mEpisodeAdapter.getPosition();
        if (pos >= 0 && pos < mEpisodeAdapter.getItemCount()) mBinding.episode.setSelectedPosition(pos);
        if (isFullscreen()) Notify.show(getString(R.string.play_ready,
                EpisodeShortName.shorten(item.getName(), getVodName())));
        onRefresh();
    }

    @Override
    protected void reverseEpisode(boolean scroll) {
        super.reverseEpisode(scroll);
        updateReverseActions();
        if (scroll) {
            int pos = mEpisodeAdapter.getPosition();
            if (pos >= 0 && pos < mEpisodeAdapter.getItemCount()) mBinding.episode.setSelectedPosition(pos);
        }
    }

    private void setArrayAdapter(int size) {
        updateReverseActions();
        List<String> items = new ArrayList<>();
        items.add(getString(mHistory != null && mHistory.isRevSort() ? R.string.play_normal_order : R.string.play_reverse));
        items.add(getString(mHistory != null ? mHistory.getRevPlayText() : R.string.play_forward));
        mBinding.array.setVisibility(size > 5 ? View.VISIBLE : View.GONE);
        if (mHistory != null && mHistory.isRevSort()) for (int i = size; i > 0; i -= 20) items.add(i + "-" + Math.max(i - 19, 1));
        else for (int i = 0; i < size; i += 20) items.add((i + 1) + "-" + Math.min(i + 20, size));
        mArrayAdapter.addAll(items);
    }

    private void updateReverseActions() {
        int label = mHistory != null && mHistory.isRevSort() ? R.string.play_normal_order : R.string.play_reverse;
        mBinding.episodeReverse.setText(label);
        mBinding.control.action.reverse.setText(label);
    }

    private void updateFocus() {
        int first = 0;
        for (int id : VideoFocusHelper.DETAIL_FOCUS_ORDER) {
            if (mFocus.isFocusTargetAvailable(id)) {
                first = id;
                break;
            }
        }
        int headerUp = mFocus.findFocusUp(R.id.episode_reverse);
        int episodeDown = mFocus.findFocusDown(R.id.episode);

        mBinding.video.setNextFocusDownId(VideoFocusHelper.targetOrSelf(first, mBinding.video));
        VideoFocusHelper.setNextFocusDown(first, mBinding.content, mBinding.keep, mBinding.change1,
                mBinding.bilibiliLogin);
        VideoFocusHelper.setVerticalFocus(headerUp, R.id.episode, mBinding.episodeReverse,
                mBinding.episodeRank, mBinding.episodeMore);

        mFlagAdapter.setNextFocusDown(VideoFocusHelper.focusTarget(mFocus.findFocusDown(R.id.flag)));
        mQualityAdapter.setNextFocus(VideoFocusHelper.focusTarget(mFocus.findFocusUp(R.id.quality)),
                VideoFocusHelper.focusTarget(mFocus.findFocusDown(R.id.quality)));
        mEpisodeAdapter.setNextFocusUp(VideoFocusHelper.focusTarget(mFocus.findFocusUp(R.id.episode)));
        mEpisodeAdapter.setNextFocusDown(VideoFocusHelper.focusTarget(episodeDown));
        mArrayAdapter.setNextFocus(VideoFocusHelper.focusTarget(mFocus.findFocusUp(R.id.array)),
                VideoFocusHelper.focusTarget(mFocus.findFocusDown(R.id.array)));
        mPartAdapter.setNextFocusUp(VideoFocusHelper.focusTarget(mFocus.findFocusUp(R.id.part)));
        mPartAdapter.setNextFocusDown(VideoFocusHelper.focusTarget(mFocus.findFocusDown(R.id.part)));
        mQuickAdapter.setNextFocusUp(VideoFocusHelper.focusTarget(mFocus.findFocusUp(R.id.quick)));

        VideoFocusHelper.applyItemFocus(mBinding.flag, View.NO_ID, mFocus.findFocusDown(R.id.flag));
        VideoFocusHelper.applyItemFocus(mBinding.quality, mFocus.findFocusUp(R.id.quality),
                mFocus.findFocusDown(R.id.quality));
        VideoFocusHelper.applyItemFocus(mBinding.episode, mFocus.findFocusUp(R.id.episode), episodeDown);
        VideoFocusHelper.applyItemFocus(mBinding.array, mFocus.findFocusUp(R.id.array), mFocus.findFocusDown(R.id.array));
        VideoFocusHelper.applyItemFocus(mBinding.part, mFocus.findFocusUp(R.id.part), mFocus.findFocusDown(R.id.part));
        VideoFocusHelper.applyItemFocus(mBinding.quick, mFocus.findFocusUp(R.id.quick), View.NO_ID);
    }

    @Override
    public void onRevSort() {
        if (mHistory == null) return;
        mHistory.setRevSort(!mHistory.isRevSort());
        reverseEpisode(false);
    }

    @Override
    public void onRevPlay(TextView view) {
        if (mHistory == null) return;
        mHistory.setRevPlay(!mHistory.isRevPlay());
        view.setText(mHistory.getRevPlayText());
        Notify.show(mHistory.getRevPlayHint());
    }

    private void onEpisodes() {
        EpisodeGridDialog.create().reverse(mHistory != null && mHistory.isRevSort()).episodes(mEpisodeAdapter.getItems()).show(this);
    }

    private void onReverse() {
        if (mHistory == null) return;
        mHistory.setRevSort(!mHistory.isRevSort());
        reverseEpisode(false);
    }

    private void onMore() {
        if (mHistory == null) return;
        EpisodeGridDialog.create().reverse(mHistory.isRevSort()).episodes(mEpisodeAdapter.getItems()).show(this);
    }

    @Override
    protected void enterFullscreen() {
        mFocus1 = getCurrentFocus();
        mBinding.video.requestFocus();
        mBinding.video.setForeground(null);
        mBinding.video.setLayoutParams(new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT));
        int flagPos = mFlagAdapter.getPosition();
        if (flagPos >= 0 && flagPos < mFlagAdapter.getItemCount()) mBinding.flag.setSelectedPosition(flagPos);
        mKeyDown.setFull(true);
        setFullscreen(true);
        setDanmakuSize();
        mFocus2 = null;
    }

    @Override
    protected void exitFullscreen() {
        mBinding.video.setForeground(ResUtil.getDrawable(R.drawable.selector_video));
        mBinding.video.setLayoutParams(mFrameParams);
        getFocus1().requestFocus();
        mKeyDown.setFull(false);
        setFullscreen(false);
        setDanmakuSize();
        mFocus2 = null;
        hideInfo();
    }

    @Override
    protected void setDanmakuSize() {
        if (service() == null) return;
        player().setDanmakuSize(isFullscreen() ? 1.2f : 0.8f);
    }

    @Override public PlayerManager getPlayerManager() { return player(); }
    @Override public History getHistory() { return mHistory; }
    @Override public void setOpening(long opening) { super.setOpening(opening); }
    @Override public void setEnding(long ending) { super.setEnding(ending); }
    @Override public int getScale() { return super.getScale(); }
    @Override public String getKey() { return super.getKey(); }
    @Override public String getVodName() { return super.getVodName(); }
    @Override public Flag getFlag() { return super.getFlag(); }
    @Override public Episode getNextEpisode() { return mEpisodeAdapter.getNext(); }
    public FragmentActivity getOwnerActivity() { return this; }

    private void onContent() {
        if (mBinding.content.getTag() == null) return;
        ContentDialog.show(this, mBinding.content.getTag().toString());
    }

    private void onKeep() {
        toggleKeep();
    }

    private void onVideo() {
        if (!isFullscreen()) enterFullscreen();
    }

    private void onChange() {
        checkSearch(true);
    }

    private void onInfo() {
        InfoDialog.create(this).title(mBinding.name.getText()).headers(player().getHeaders()).url(player().getUrl()).show();
    }

    @Override
    public void onShare(CharSequence title) {
    }

    @Override
    protected void checkNext(boolean notify) {
        if (mHistory == null) return;
        if (EpisodeAdvancePolicy.playbackDirection(true, mHistory.isRevPlay()) == EpisodeAdvancePolicy.Direction.NEXT) onNext(notify, 1);
        else onPrev(notify, 1);
    }

    @Override
    protected void checkPrev() {
        if (mHistory == null) return;
        if (EpisodeAdvancePolicy.playbackDirection(false, mHistory.isRevPlay()) == EpisodeAdvancePolicy.Direction.NEXT) onNext(true, -1);
        else onPrev(true, -1);
    }

    private void onNext(boolean notify, int playlistOffset) {
        Episode item = mEpisodeAdapter.getNext();
        if (item == null) {
            if (!advancePlaylist(playlistOffset) && notify && mHistory != null) Notify.show(mHistory.isRevPlay() ? R.string.error_play_prev : R.string.error_play_next);
        } else if (!item.isActivated()) {
            onItemClick(item);
        } else {
            if (!advancePlaylist(playlistOffset) && notify && mHistory != null) Notify.show(mHistory.isRevPlay() ? R.string.error_play_prev : R.string.error_play_next);
        }
    }

    private void onPrev(boolean notify, int playlistOffset) {
        Episode item = mEpisodeAdapter.getPrev();
        if (item == null) {
            if (!advancePlaylist(playlistOffset) && notify && mHistory != null) Notify.show(mHistory.isRevPlay() ? R.string.error_play_next : R.string.error_play_prev);
        } else if (!item.isActivated()) {
            onItemClick(item);
        } else {
            if (!advancePlaylist(playlistOffset) && notify && mHistory != null) Notify.show(mHistory.isRevPlay() ? R.string.error_play_next : R.string.error_play_prev);
        }
    }

    private boolean advancePlaylist(int offset) {
        if (!getIntent().getBooleanExtra("playlist", false) || mHistory == null) return false;
        boolean wasActive = PlaylistStore.get().isActive();
        History next = PlaylistStore.get().advance(mHistory, offset);
        if (next == null) {
            if (wasActive) Notify.show(R.string.playlist_finished);
            return wasActive;
        }
        Config config = Config.find(next.getCid());
        if (config == null) {
            PlaylistStore.get().pause();
            Notify.show(R.string.playlist_source_unavailable);
            return true;
        }
        if (next.getCid() != VodConfig.getCid()) {
            VodConfig.load(config, lifecycleCallback(new Callback() {
                @Override public void success() { startPlaylist(getActivity(), next); }
                @Override public void error(String msg) { PlaylistStore.get().pause(); Notify.show(msg); }
            }));
        } else {
            startPlaylist(this, next);
        }
        return true;
    }




    private boolean onResetToggle() {
        AppSettings.putReset(Math.abs(AppSettings.getReset() - 1));
        mBinding.control.action.reset.setText(ResUtil.getStringArray(R.array.select_reset)[AppSettings.getReset()]);
        return true;
    }



    private void onChoose() {
        PlayerHelper.choose(this, player().getUrl(), player().getHeaders(), player().isVod(), player().getPosition(), mBinding.widget.overlayTitle.getText());
        setRedirect(true);
    }

    private void onDecode() {
        mClock.setCallback(null);
        player().toggleDecode();
        setDecode();
    }



    private void onTitle() {
        hideControl();
    }




    public void onCastDialog() {
        mCast.onCast();
    }


    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onCastEvent(CastEvent event) {
        if (isRedirect()) return;
        ReceiveDialog.create().event(event).show(this);
    }

    private void onToggle() {
        if (isVisible(mBinding.control.getRoot())) hideControl();
        else showControl(getFocus2());
    }

    @Override
    protected void showProgress() {
        mBinding.progress.getRoot().setVisibility(View.VISIBLE);
        App.post(mSetTrafficRunnable, 0);
        hideCenter();
        hideError();
    }

    @Override
    protected void hideProgress() {
        mBinding.progress.getRoot().setVisibility(View.GONE);
    }

    @Override
    protected void showError(String text) {
        mBinding.widget.error.setVisibility(View.VISIBLE);
        mBinding.widget.overlayText.setText(text);
        hideProgress();
    }

    @Override
    protected void hideError() {
        mBinding.widget.error.setVisibility(View.GONE);
        mBinding.widget.overlayText.setText("");
    }

    private void showInfo() {
        mBinding.widget.top.setVisibility(View.VISIBLE);
        mBinding.widget.center.setVisibility(View.VISIBLE);
        mBinding.videoInfo.setVisibility(View.GONE);
        mBinding.videoClock.setVisibility(View.GONE);
        mBinding.widget.duration.setText(player().getDurationTime());
        mBinding.widget.position.setText(player().getPositionTime(0));
    }

    private void hideInfo() {
        mBinding.widget.top.setVisibility(View.GONE);
        mBinding.widget.center.setVisibility(View.GONE);
        if (PlaybackOverlayState.shouldShowVideoInfo(PlayerSettings.getVideoInfo(), isFullscreen())) mBinding.videoInfo.setVisibility(View.VISIBLE);
        if (PlayerSettings.showVideoClock()) mBinding.videoClock.setVisibility(View.VISIBLE);
    }

    private void showControl(View view) {
        mBinding.control.getRoot().setVisibility(View.VISIBLE);
        view.requestFocus();
        setR1Callback();
    }

    @Override
    protected void showControl() {
        showControl(getFocus2());
    }

    @Override
    public void hideControl() {
        mBinding.control.getRoot().setVisibility(View.GONE);
        App.removeCallbacks(mHideControlRunnable);
    }

    private void hideCenter() {
        mBinding.widget.overlayAction.setImageResource(R.drawable.ic_widget_play);
        hideInfo();
    }

    @Override
    protected void setTraffic() {
        super.setTraffic();
        if (PlayerSettings.showVideoInfo() || PlayerSettings.showVideoClock()) updateVideoInfo();
        App.post(mSetTrafficRunnable, 1000);
    }



    private void setR2Callback() {
        App.removeCallbacks(mUpdateFocusRunnable);
        App.post(mUpdateFocusRunnable, 0);
    }

    private void setPartAdapter() {
        if (mHistory == null) return;
        mPartAdapter.addAll(PartUtil.split(mHistory.getVodName()));
        mBinding.part.setVisibility(View.VISIBLE);
        setR2Callback();
    }

    @Override
    protected void onHistoryLoaded() {
        super.onHistoryLoaded();
        if (isPlaylistPlayback()) {
            PlaylistPlaybackPolicy.prepareHistory(mHistory, playlistSelection());
        }
        setPartAdapter();
    }

    private boolean isPlaylistPlayback() {
        return getIntent().getBooleanExtra("playlist", false);
    }

    private PlaylistEpisodeSelection playlistSelection() {
        if (!isPlaylistPlayback()) return null;
        return PlaylistStore.get().selection(getIntent().getStringExtra("playlistKey"));
    }

    @Override
    protected void setKeepImage(boolean kept) {
        mBinding.keep.setCompoundDrawablesWithIntrinsicBounds(kept ? R.drawable.ic_detail_keep_on : R.drawable.ic_detail_keep_off, 0, 0, 0);
    }

    @Override
    protected void updateVod(Vod item) {
        super.updateVod(item);
        boolean name = !item.getName().isEmpty();
        if (name) mBinding.widget.overlayTitle.setText(VodShortName.shorten(item.getName()));
        if (name) setPartAdapter();
    }

    @Override
    protected void initSearch(String keyword, boolean auto) {
        super.initSearch(keyword, auto);
        mBinding.part.setTag(keyword);
    }

    @Override
    protected boolean mismatch(Vod item) {
        if (getId().equals(item.getId())) return true;
        if (mBroken.contains(item.getId())) return true;
        String keyword = Objects.toString(mBinding.part.getTag(), "");
        if (isAutoMode()) return !item.getName().equals(keyword);
        else return !item.getName().contains(keyword);
    }

    @Override
    protected void showEmpty() {
        mBinding.progressLayout.showEmpty();
    }

    private View getFocus1() {
        return mFocus1 == null || mFocus1.getVisibility() != View.VISIBLE ? mBinding.video : mFocus1;
    }

    private View getFocus2() {
        return mFocus2 == null || mFocus2.getVisibility() != View.VISIBLE || mFocus2 == mBinding.control.action.opening || mFocus2 == mBinding.control.action.ending ? mBinding.control.action.next : mFocus2;
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
        if (isFullscreen()) {
            if (remoteMappings == null) remoteMappings = new TvRemoteMappings(this);
            event = remoteMappings.translate(event);
        }
        if (isFullscreen() && service() != null && TvRemoteKeys.dispatch(event, this::onRemoteCommand)) return true;
        if (isFullscreen() && service() != null &&
                (event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_NEXT || event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PREVIOUS)) {
            if (KeyUtil.isActionUp(event) && !event.isCanceled()) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_NEXT) checkNext(true);
                else checkPrev();
            }
            return true;
        }
        if (isVisible(mBinding.control.getRoot())) setR1Callback();
        if (isVisible(mBinding.control.getRoot())) mFocus2 = getCurrentFocus();
        if (isFullscreen() && isGone(mBinding.control.getRoot()) && mKeyDown.hasEvent(event) && service() != null) return mKeyDown.onKeyDown(event);
        if (KeyUtil.isMediaFastForward(event)) return onSeekForward();
        if (KeyUtil.isMediaRewind(event)) return onSeekBack();
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onPlayingChanged(boolean isPlaying) {
        updateVideoNetworkWarning(isPlaying);
        if (isPlaying) {
            hideCenter();
        } else if (isPaused()) {
            if (isFullscreen()) showInfo();
            else hideInfo();
        }
    }

    @Override
    protected void onSizeChanged(VideoSize size) {
        updateVideoInfo();
    }

    private boolean mCastAudioTrackApplied;

    @Override
    protected void onTracksChanged() {
        applyCastAudioTrack();
    }

    private void applyCastAudioTrack() {
        if (mCastAudioTrackApplied) return;
        String audioTrackFormat = getCastAudioTrackFormat();
        if (audioTrackFormat == null) return;
        PlayerManager player = player();
        if (player == null || player.isEmpty()) return;
        mCastAudioTrackApplied = true;
        Tracks tracks = player.getCurrentTracks();
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_AUDIO) continue;
            for (int i = 0; i < group.length; i++) {
                Format format = group.getTrackFormat(i);
                if (audioTrackFormat.equals(PlayerHelper.describeFormat(format))) {
                    Track track = new Track(C.TRACK_TYPE_AUDIO, format.label != null ? format.label : "", audioTrackFormat);
                    track.setSelected(true);
                    track.key(player.getKey());
                    player.setTrack(java.util.Arrays.asList(track));
                    return;
                }
            }
        }
    }

    private void onVideoInfo() {
        int currentMode = PlayerSettings.getVideoInfo();
        int newMode = (currentMode + 1) % 4; // Cycle through 0, 1, 2, 3
        PlayerSettings.putVideoInfo(newMode);
        mBinding.control.action.actionInfo.setActivated(newMode != 3);
        updateVideoInfo();
    }

    private void updateVideoInfo() {
        renderPlaybackOverlay(mBinding.progress.traffic.getText());
        Flag flag = getFlag();
        Episode episode = getEpisode();
        if (flag != null) {
            int total = flag.getEpisodes().size();
            String episodeInfo = EpisodeDisplayHelper.buildDisplayText(mBinding.name.getText(), episode.getDisplayName(), total);
            CharSequence existing = mBinding.videoInfo.getText();
            if (existing.length() > 0) {
                mBinding.videoInfo.setText(PlaybackOverlayState.join(existing.toString(), episodeInfo));
            } else {
                mBinding.videoInfo.setText(episodeInfo);
                mBinding.videoInfo.setVisibility(PlaybackOverlayState.shouldShowVideoInfo(PlayerSettings.getVideoInfo(), isFullscreen()) ? View.VISIBLE : View.GONE);
            }
        }
    }

    @Override
    public void onSubtitleClick() {
        SubtitleDialog.create().view(mBinding.exo.getSubtitleView()).full(isFullscreen()).show(this);
        App.post(mHideControlRunnable, 100);
    }

    @Override
    public void onSeeking(long time) {
        mBinding.widget.center.setVisibility(View.VISIBLE);
        mBinding.widget.duration.setText(player().getDurationTime());
        mBinding.widget.position.setText(player().getPositionTime(time));
        mBinding.widget.overlayAction.setImageResource(time > 0 ? R.drawable.ic_widget_forward : R.drawable.ic_widget_rewind);
        hideProgress();
    }

    @Override
    public void onSeekEnd(long time) {
        mKeyDown.reset();
        seekTo(time);
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
    public void onKeyUp() {
        long position = player().getPosition();
        long duration = player().getDuration();
        if (player().canSetOpening(position, duration)) {
            showControl(mBinding.control.action.opening);
        } else if (player().canSetEnding(position, duration)) {
            showControl(mBinding.control.action.ending);
        } else {
            showControl(getFocus2());
        }
    }

    @Override
    public void onKeyDown() {
        showControl(getFocus2());
    }

    @Override
    public void onKeyCenter() {
        if (player().isPlaying()) onPaused();
        else if (player().isEmpty()) onRefresh();
        else onPlay();
        hideControl();
    }

    @Override
    public void onSingleTap() {
        if (isFullscreen()) onToggle();
    }

    @Override
    public void onDoubleTap() {
        if (isFullscreen()) onKeyCenter();
    }

    private boolean onSeekBack() {
        controller().seekBack();
        return true;
    }

    private boolean onSeekForward() {
        controller().seekForward();
        return true;
    }

    private void checkCast() {
        if (isCast() && !isFullscreen()) enterFullscreen();
        else mBinding.progressLayout.showProgress();
    }



    @Override
    protected void onStart() {
        super.onStart();
        mClock.stop().start();
        mVideoClock.stop().start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (PlayerSettings.isBackgroundOff()) mClock.stop();
        if (PlayerSettings.isBackgroundOff()) mVideoClock.stop();
    }

    @Override
    protected void onBackInvoked() {
        if (isVisible(mBinding.control.getRoot())) {
            hideControl();
        } else if (isVisible(mBinding.widget.center)) {
            hideCenter();
        } else if (isFullscreen()) {
            exitFullscreen();
        } else {
            mViewModel.stopSearch();
            startHomeAsRoot();
            super.onBackInvoked();
        }
    }

    @Override
    protected void onDestroy() {
        Timer.get().reset();
        App.removeCallbacks(mUpdateFocusRunnable, mSetTrafficRunnable);
        Traffic.reset();
        mKeyDown = null;
        mArrayAdapter = null;
        mPartAdapter = null;
        mFocus1 = null;
        mFocus2 = null;
        mUpdateFocusRunnable = null;
        mSetTrafficRunnable = null;
        super.onDestroy();
    }
}
