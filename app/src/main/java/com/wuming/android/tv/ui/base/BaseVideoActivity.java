package com.wuming.android.tv.ui.base;
import com.wuming.android.tv.cloud.opendrive.OpenDriveLog;
import com.wuming.android.tv.setting.DisplaySettings;

import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.style.ClickableSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.C;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.request.transition.Transition;
import com.wuming.android.tv.App;
import com.wuming.android.tv.Constant;
import com.wuming.android.tv.cloud.opendrive.DriveLoginHelper;
import com.wuming.android.tv.R;
import com.wuming.android.tv.api.DetailPlaybackAvailability;
import com.wuming.android.tv.api.SiteApi;
import com.wuming.android.tv.api.config.VodConfig;
import com.wuming.android.tv.bean.BeanUtils;
import com.wuming.android.tv.bean.Danmaku;
import com.wuming.android.tv.bean.Episode;
import com.wuming.android.tv.bean.EpisodeShortName;
import com.wuming.android.tv.bean.VodShortName;
import com.wuming.android.tv.bean.Flag;
import com.wuming.android.tv.bean.History;
import com.wuming.android.tv.bean.Keep;
import com.wuming.android.tv.bean.Parse;
import com.wuming.android.tv.bean.QrChallenge;
import com.wuming.android.tv.bean.Result;
import com.wuming.android.tv.bean.Site;
import com.wuming.android.tv.bean.Sub;
import com.wuming.android.tv.bean.Track;
import com.wuming.android.tv.bean.Vod;
import com.wuming.android.tv.databinding.ActivityVideoBinding;
import com.wuming.android.tv.db.AppDatabase;
import com.wuming.android.tv.event.RefreshEvent;
import com.wuming.android.tv.impl.CustomTarget;
import com.wuming.android.tv.library.UserLibrary;
import com.wuming.tv.core.media.OpenDriveUri;
import com.wuming.android.tv.media.PlaybackRoutePolicy;
import com.wuming.tv.core.media.HistoryPlaybackFallback;
import com.wuming.android.tv.model.SiteViewModel;
import com.wuming.android.tv.player.PlayerManager;
import com.wuming.android.tv.setting.AppSettings;
import com.wuming.android.tv.service.PlaybackService;
import com.wuming.android.tv.ui.adapter.EpisodeAdapter;
import com.wuming.android.tv.ui.adapter.FlagAdapter;
import com.wuming.android.tv.ui.adapter.ParseAdapter;
import com.wuming.android.tv.ui.adapter.QualityAdapter;
import com.wuming.android.tv.ui.adapter.QuickAdapter;
import com.wuming.android.tv.utils.Clock;
import com.wuming.android.tv.utils.Bilibili;
import com.wuming.android.tv.utils.ImgUtil;
import com.wuming.android.tv.utils.Notify;
import com.wuming.android.tv.utils.Task;
import com.wuming.android.tv.utils.Traffic;
import com.wuming.android.tv.utils.UiSafe;
import com.wuming.android.tv.utils.Util;
import com.github.bassaer.library.MDColor;

import com.wuming.android.tv.event.CastEvent;
import com.wuming.android.tv.player.PlayerHelper;
import com.wuming.android.tv.ui.dialog.DanmakuDialog;
import com.wuming.android.tv.ui.dialog.ReceiveDialog;
import com.wuming.android.tv.ui.dialog.TrackDialog;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import okhttp3.Callback;

public abstract class BaseVideoActivity extends PlaybackActivity implements Clock.Callback {

    protected ActivityVideoBinding mBinding;
    protected ViewGroup.LayoutParams mFrameParams;
    protected Observer<Result> mObserveDetail;
    protected Observer<Result> mObservePlayer;
    protected Observer<Result> mObserveSearch;
    protected EpisodeAdapter mEpisodeAdapter;
    protected QualityAdapter mQualityAdapter;
    protected QuickAdapter mQuickAdapter;
    protected ParseAdapter mParseAdapter;
    protected SiteViewModel mViewModel;
    protected FlagAdapter mFlagAdapter;
    protected Set<String> mBroken = new LinkedHashSet<>();
    protected History mHistory;
    protected Vod mCurrentVod;
    protected boolean fullscreen;
    protected boolean initAuto;
    protected boolean autoMode;
    protected boolean useParse;
    protected boolean hasRealHistory;
    protected Runnable mHideControlRunnable;
    protected Runnable mShowEmptyRunnable;
    protected Runnable mAutoPlayFirstEpisodeRunnable;
    protected Clock mClock;
    protected Clock mVideoClock;
    private CustomTarget<Drawable> mArtworkTarget;
    // Login state lives in PlaybackLoginFlow; the calls below stay qualified because Host
    // declares the same method names as this activity.
    private final PlaybackLoginFlow mLogin = new PlaybackLoginFlow(this, new PlaybackLoginFlow.Host() {
        @Override public boolean isUiActive() { return BaseVideoActivity.this.isUiActive(); }
        @Override public boolean isBaiduLogin() { return BaseVideoActivity.this.isBaiduLogin(); }
        @Override public void startFlow() { BaseVideoActivity.this.startFlow(); }
        @Override public void showError(String text) { BaseVideoActivity.this.showError(text); }
        @Override public void retryPlayback() { getPlayer(getFlag(), getEpisode()); }
    });
    private final VideoHistoryLoader mHistoryLoader = new VideoHistoryLoader(new VideoHistoryLoader.Host() {
        @Override public String activeKey() { return isUiActive() ? getHistoryKey() : null; }
        @Override public void restored(String key, String mark, Vod item, History history, boolean existing) {
            applyHistory(key, mark, item, history, existing);
        }
    });
    private boolean mVideoPlaying;
    private PlaybackNetworkObserver mNetworkObserver;
    private final VideoFavoritesController mFavorites = new VideoFavoritesController(new VideoFavoritesController.Host() {
        @Override public String activeKey() {
            return isUiActive() ? getHistoryKey() : null;
        }
        @Override public void showFavorite(boolean kept, boolean notify) {
            if (notify) Notify.show(kept ? R.string.keep_add : R.string.keep_del);
            setKeepImage(kept);
        }
    });
    private final VideoPlaybackSession mPlaybackSession = new VideoPlaybackSession();
    private final VideoDetailLoadCoordinator<Vod> mDetailLoadCoordinator =
            new VideoDetailLoadCoordinator<>();

    protected static class DetailObserver implements Observer<Result> {
        private final WeakReference<BaseVideoActivity> ref;

        public DetailObserver(BaseVideoActivity activity) {
            this.ref = new WeakReference<>(activity);
        }

        @Override
        public void onChanged(Result result) {
            BaseVideoActivity activity = ref.get();
            if (activity != null && result != null) activity.setDetail(result);
        }
    }

    protected static class PlayerObserver implements Observer<Result> {
        private final WeakReference<BaseVideoActivity> ref;

        public PlayerObserver(BaseVideoActivity activity) {
            this.ref = new WeakReference<>(activity);
        }

        @Override
        public void onChanged(Result result) {
            BaseVideoActivity activity = ref.get();
            if (activity != null && result != null) activity.setPlayer(result);
        }
    }

    protected static class SearchObserver implements Observer<Result> {
        private final WeakReference<BaseVideoActivity> ref;

        public SearchObserver(BaseVideoActivity activity) {
            this.ref = new WeakReference<>(activity);
        }

        @Override
        public void onChanged(Result result) {
            BaseVideoActivity activity = ref.get();
            if (activity != null && result != null) activity.setSearch(result);
        }
    }

    private static class AutoPlayRunnable implements Runnable {
        private final WeakReference<BaseVideoActivity> activityRef;
        private final Episode episode;

        AutoPlayRunnable(BaseVideoActivity activity, Episode episode) {
            this.activityRef = new WeakReference<>(activity);
            this.episode = episode;
        }

        @Override
        public void run() {
            BaseVideoActivity activity = activityRef.get();
            if (activity == null || activity.mAutoPlayFirstEpisodeRunnable != this) return;
            activity.mAutoPlayFirstEpisodeRunnable = null;
            if (activity.mEpisodeAdapter == null || activity.hasRealHistory || episode.isActivated()) return;
            if (activity.mEpisodeAdapter.getPosition(episode) >= 0) activity.onItemClick(episode);
        }
    }

    // Intent data accessors

    protected String getName() {
        return Objects.toString(getIntent().getStringExtra("name"), "");
    }

    protected String getPic() {
        return Objects.toString(getIntent().getStringExtra("pic"), "");
    }

    protected String getMark() {
        return Objects.toString(getIntent().getStringExtra("mark"), "");
    }

    protected String getKey() {
        return Objects.toString(getIntent().getStringExtra("key"), "");
    }

    protected String getId() {
        return Objects.toString(getIntent().getStringExtra("id"), "");
    }

    protected String getHistoryKey() {
        return getKey().concat(AppDatabase.SYMBOL).concat(getId()).concat(AppDatabase.SYMBOL) + VodConfig.getCid();
    }

    protected Site getSite() {
        return VodConfig.get().getSite(getKey());
    }

    protected Flag getFlag() {
        return mFlagAdapter.isEmpty() ? null : mFlagAdapter.getActivated();
    }

    protected Episode getEpisode() {
        return mEpisodeAdapter.isEmpty() ? new Episode() : mEpisodeAdapter.getActivated();
    }

    protected int getScale() {
        return mHistory != null && mHistory.getScale() != -1 ? mHistory.getScale() : DisplaySettings.getScale();
    }

    protected boolean isReplay() {
        return AppSettings.getReset() == 1;
    }

    protected final void updateVideoNetworkWarning(boolean playing) {
        mVideoPlaying = playing;
        updateVideoNetworkWarning();
    }

    private void updateVideoNetworkWarning() {
        if (mBinding == null) return;
        PlaybackOverlayState state = PlaybackOverlayState.create(
                player(), mBinding.progress.traffic.getText(), mVideoPlaying, isFullscreen());
        int message = state.networkWarning();
        mBinding.networkWarning.setVisibility(message == 0 ? View.GONE : View.VISIBLE);
        if (message != 0) mBinding.networkWarning.setText(message);
    }

    protected final void renderPlaybackOverlay(CharSequence traffic) {
        if (mBinding == null) return;
        PlaybackOverlayState state = PlaybackOverlayState.create(player(), traffic, mVideoPlaying, isFullscreen());
        mBinding.videoInfo.setText(state.videoInfo());
        mBinding.videoInfo.setVisibility(state.showVideoInfo() ? View.VISIBLE : View.GONE);
        mBinding.videoClock.setVisibility(state.showClock() ? View.VISIBLE : View.GONE);
        int warning = state.networkWarning();
        mBinding.networkWarning.setVisibility(warning == 0 ? View.GONE : View.VISIBLE);
        if (warning != 0) mBinding.networkWarning.setText(warning);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mNetworkObserver == null) {
            mNetworkObserver = new PlaybackNetworkObserver(this, () -> {
                if (isUiActive()) updateVideoNetworkWarning();
            });
        }
        mNetworkObserver.start();
        updateVideoNetworkWarning();
    }

    @Override
    protected void onStop() {
        if (mNetworkObserver != null) mNetworkObserver.stop();
        super.onStop();
    }

    protected boolean isFromCollect() {
        return getIntent().getBooleanExtra("collect", false);
    }

    protected boolean isFullscreen() {
        return fullscreen;
    }

    protected void setFullscreen(boolean fullscreen) {
        this.fullscreen = fullscreen;
    }

    protected boolean isInitAuto() {
        return initAuto;
    }

    protected void setInitAuto(boolean initAuto) {
        this.initAuto = initAuto;
    }

    protected boolean isAutoMode() {
        return autoMode;
    }

    protected void setAutoMode(boolean autoMode) {
        this.autoMode = autoMode;
    }

    public boolean isUseParse() {
        return useParse;
    }

    public void setUseParse(boolean useParse) {
        this.useParse = useParse;
    }

    // Shared static start methods

    public static void push(androidx.fragment.app.FragmentActivity activity, String text) {
        if (BeanUtils.isEmpty(text)) return;
        android.net.Uri uri = android.net.Uri.parse(text);
        if (com.wuming.android.tv.utils.FileChooser.isValid(activity, uri))
            com.wuming.android.tv.utils.FileChooser.importFile(activity, uri, path -> file(activity, path));
        else start(activity, com.wuming.android.tv.utils.Sniffer.getUrl(text));
    }

    public static void file(androidx.fragment.app.FragmentActivity activity, String path) {
        if (BeanUtils.isEmpty(path)) return;
        String name = new java.io.File(path).getName();
        start(activity, SiteApi.PUSH, "file://" + path, name);
    }

    public static void start(android.app.Activity activity, String url) {
        start(activity, SiteApi.PUSH, url, url);
    }

    public static void start(android.app.Activity activity, String key, String id, String name) {
        start(activity, key, id, name, null);
    }

    public static void start(android.app.Activity activity, String key, String id, String name, String pic) {
        start(activity, key, id, name, pic, null);
    }

    public static void start(android.app.Activity activity, String key, String id, String name, String pic, String mark) {
        start(activity, key, id, name, pic, mark, false);
    }

    // Static methods are not polymorphic. Dispatch explicitly to the activity supplied by the
    // Android flavor; inherited URL/file overloads must not terminate in a throwing base stub.
    public static void start(android.app.Activity activity, String key, String id, String name, String pic, String mark, boolean collect) {
        com.wuming.android.tv.ui.activity.VideoActivity.start(activity, key, id, name, pic, mark, collect);
    }

    // Shared business logic

    protected void checkId() {
        if (getId().startsWith("push://")) getIntent().putExtra("key", SiteApi.PUSH).putExtra("id", getId().substring(7));
        if (getId().isEmpty() || getId().startsWith("msearch:")) setEmpty(false);
        else getDetail();
    }

    protected void getDetail() {
        cancelAutoPlay();
        mHistoryLoader.cancel();
        mDetailLoadCoordinator.discardPending();
        releaseQrResources();
        mViewModel.detailContent(getKey(), getId());
    }

    /** Completes the playback-dependent part of detail setup after service binding. */
    protected final void onVideoPlaybackReady() {
        Vod pending = mDetailLoadCoordinator.onPlaybackReady();
        if (pending != null) checkFlag(pending);
    }

    protected void getDetail(Vod item) {
        getIntent().putExtra("key", item.getSiteKey());
        getIntent().putExtra("pic", item.getPic());
        getIntent().putExtra("id", item.getId());
        getIntent().putExtra("name", item.getName());
        mBinding.scroll.scrollTo(0, 0);
        mClock.setCallback(null);
        PlayerManager p = player();
        if (p != null) {
            p.reset();
            p.stop();
        }
        saveHistory();
        mPlaybackSession.finish(System.currentTimeMillis(), false);
        getDetail();
    }

    protected void setDetail(Result result) {
        if (result.getList().isEmpty()) setEmpty(false);
        else setDetail(result.getVod());
        Notify.show(result.getMsg());
    }

    protected void setEmpty(boolean finish) {
        if (isFromCollect() || finish) {
            finish();
        } else if (getName().isEmpty()) {
            showEmpty();
        } else {
            mBinding.name.setText(getName());
            App.post(mShowEmptyRunnable, 10000);
            checkSearch(false);
        }
    }

    protected void setDetail(Vod item) {
        cancelAutoPlay();
        mCurrentVod = item;
        item.checkPic(getPic());
        item.checkName(getName());
        mBinding.progressLayout.showContent();
        mBinding.name.setText(VodShortName.shorten(item.getName()));
        mFlagAdapter.addAll(item.getFlags());
        setEpisodeAdapter(java.util.Collections.emptyList());
        App.removeCallbacks(mShowEmptyRunnable);
        checkHistory(item);
        checkKeepImg();
        setText(item);
    }

    protected void setText(Vod item) {
        setText(mBinding.site, R.string.detail_site, getSite().getName());
        setText(mBinding.director, R.string.detail_director, item.getDirector());
        setText(mBinding.actor, R.string.detail_actor, item.getActor());
        setText(mBinding.remark, 0, item.getRemarks());
    }

    protected void setText(TextView view, int resId, String text) {
        if (BeanUtils.isEmpty(text)) {
            view.setText("");
            view.setVisibility(View.GONE);
            return;
        }
        view.setText(com.wuming.android.tv.utils.Sniffer.buildClickable(resId > 0 ? getString(resId, text) : text, this::clickableSpan), TextView.BufferType.SPANNABLE);
        view.setVisibility(View.VISIBLE);
        view.setLinkTextColor(MDColor.YELLOW_500);
        com.wuming.android.tv.ui.custom.CustomMovement.bind(view);
    }

    protected abstract ClickableSpan clickableSpan(Result result);

    protected void getPlayer(Flag flag, Episode episode) {
        releaseQrResources();
        mPlaybackSession.beginSelection();
        if (flag == null) {
            onError(getString(R.string.error_play_url));
            return;
        }
        // Let the provider create its TV login session. If authentication is
        // needed, its response contains the QR image and retains the query token.
        String episodeUrl = episode.getUrl();
        if (isBilibiliSite()) {
            boolean loggedIn = com.github.catvod.net.PersistentCookieJar.get()
                    .hasLoginCookies("https://www.bilibili.com/");
            episodeUrl = Bilibili.normalizeEpisodeUrl(episodeUrl, loggedIn);
        }
        if (BeanUtils.isEmpty(episodeUrl) && mHistory != null
                && mHistory.matchesPlaybackSelection(flag.getFlag(), episode)) {
            episodeUrl = mHistory.getEpisodeUrl();
        }
        Episode playbackEpisode = Episode.create(episode.getName(), episodeUrl);
        String playbackId = PlaybackRoutePolicy.select(AppSettings.getLinkRoute(), mHistory,
                flag.getFlag(), playbackEpisode);
        if (OpenDriveUri.isValid(playbackId)) {
            mPlaybackSession.armFallback(flag.getFlag(), episodeUrl, null);
        } else {
            clearHistoryFallback();
            playbackId = episodeUrl;
        }
        mViewModel.playerContent(getKey(), flag.getFlag(), playbackId);
        updateHistory(episode);
        showProgress();
    }

    protected void setPlayer(Result result) {
        Map<String, String> fallbackHeaders = mPlaybackSession.consumePendingFallbackHeaders();
        if (fallbackHeaders != null && !fallbackHeaders.isEmpty()) {
            result.mergeHeader(fallbackHeaders);
        }
        if (result.hasMsg()) {
            mViewModel.clearPlayer(result);
            if (!com.wuming.android.tv.cloud.opendrive.QuarkSmartQualityException.matches(result.getMsg())
                    && retryHistoryFallback(result.getHeader())) return;
            onError(result.getMsg());
            return;
        }
        int cloudNotice = com.wuming.android.tv.cloud.opendrive.OpenDriveMessages.fallback(result.getHeader());
        if (cloudNotice != 0) Notify.show(cloudNotice);
        mQualityAdapter.addAll(result);
        setUseParse(result.shouldUseParse());
        setQualityVisible(result.getUrl().isMulti());
        result.getUrl().set(result.getUrl().getPosition());

        // Check for dead link (no valid URL)
        if (result.getUrl().isEmpty()) {
            PlayerManager currentPlayer = player();
            if (currentPlayer != null) currentPlayer.recordDeadLink(getKey());
            if (retryHistoryFallback(result.getHeader())) return;
            onError(getString(R.string.error_play_url));
            return;
        }

        if (mHistory != null && result.hasMediaUri()) mHistory.setMediaUri(result.getMediaUri());
        if (result.hasArtwork()) setArtwork(result.getArtwork());
        if (result.hasPosition() && mHistory != null) mHistory.setPosition(result.getPosition());
        mBinding.control.parse.setVisibility(isUseParse() ? View.VISIBLE : View.GONE);
        startPlayer(getHistoryKey(), result, isUseParse(), getSite().getTimeout(), buildMetadata());
    }

    protected void setParse(Parse item) {
        VodConfig.get().setParse(item);
        notifyItemChanged(mBinding.control.parse, mParseAdapter);
    }

    protected void setQualityVisible(boolean visible) {
        mBinding.quality.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    protected void reverseEpisode(boolean scroll) {
        mFlagAdapter.reverse();
        Flag flag = getFlag();
        if (flag != null) setEpisodeAdapter(flag.getEpisodes());
    }

    protected boolean shouldEnterFullscreen(Episode item) {
        boolean enter = !isFullscreen() && item.isActivated();
        if (enter) enterFullscreen();
        return enter;
    }

    // Abstract methods for variant-specific behavior

    protected abstract void enterFullscreen();

    protected abstract void exitFullscreen();

    protected abstract void showEmpty();

    protected abstract void showProgress();

    protected abstract void hideProgress();

    protected abstract void showError(String text);

    protected abstract void hideError();

    protected abstract void showControl();

    protected abstract void hideControl();

    protected abstract void setEpisodeAdapter(List<Episode> items);

    protected abstract void setDecode();

    protected abstract void setScale(int scale);

    protected abstract void setDanmakuSize();

    protected abstract void setKeepImage(boolean kept);

    protected void notifyItemChanged(RecyclerView view, RecyclerView.Adapter<?> adapter) {
        UiSafe.notifyDataSetChanged(view, adapter);
    }

    /**
     * Original (unshortened) VOD title. This — not the text rendered in the title view —
     * is the context {@link EpisodeShortName} expects, otherwise the same episode name
     * would be cached differently depending on which surface warmed the cache first.
     */
    protected String getVodName() {
        return mHistory == null ? "" : mHistory.getVodName();
    }

    protected String getVideoRankTitle() {
        return getVodName();
    }

    // Shared history/keep logic

    protected void checkHistory(Vod item) {
        mHistory = null;
        hasRealHistory = false;
        mHistoryLoader.load(getHistoryKey(), VodConfig.getCid(), getMark(), item);
    }

    private void applyHistory(String key, String mark, Vod item, History history, boolean existing) {
        hasRealHistory = existing;
        mHistory = history;
        if (BeanUtils.isNotEmpty(mark)) mHistory.setVodRemarks(mark);
        if (AppSettings.isIncognito() && mHistory.getKey().equals(key)) {
            History current = mHistory;
            Task.execute(() -> UserLibrary.get().deleteHistory(current));
        }
        mBinding.control.action.opening.setText(mHistory.getOpening() <= 0 ? getString(R.string.play_op) : Util.timeMs(mHistory.getOpening()));
        mBinding.control.action.ending.setText(mHistory.getEnding() <= 0 ? getString(R.string.play_ed) : Util.timeMs(mHistory.getEnding()));
        PlayerManager currentPlayer = player();
        if (currentPlayer != null) mBinding.control.action.speed.setText(currentPlayer.setSpeed(mHistory.getSpeed()));
        mHistory.setVodName(item.getName());
        setArtwork(item.getPic());
        setScale(getScale());
        onHistoryLoaded();
        updateKeep();
        Vod ready = mDetailLoadCoordinator.onDetailReady(item);
        if (ready != null) checkFlag(ready);
    }

    protected void onHistoryLoaded() {
        // Override in subclasses to perform actions after mHistory is loaded
    }

    protected void saveHistory() {
        saveHistory(false);
    }

    protected void saveHistory(boolean exit) {
        History history = mHistory;
        if (history != null && history.canSave() && !AppSettings.isIncognito()) Task.execute(() -> {
            try {
                UserLibrary.get().mergeAndSave(history);
            } catch (Exception e) {
                android.util.Log.e("BaseVideoActivity", "saveHistory failed", e);
            }
            if (exit) RefreshEvent.history();
        });
    }

    protected void syncHistory() {
        History history = mHistory;
        if (history != null && !AppSettings.isIncognito()) {
            Task.execute(() -> UserLibrary.get().saveHistory(history));
        }
    }

    protected void updateHistory(Episode item) {
        if (mHistory == null) return;
        Flag flag = getFlag();
        boolean sameEpisode = item.matchesName(mHistory.getEpisode());
        boolean sameFlag = flag != null && Objects.equals(flag.getFlag(), mHistory.getVodFlag());
        mHistory.setPosition(sameEpisode ? mHistory.getPosition() : C.TIME_UNSET);
        if (!sameEpisode || !sameFlag) mHistory.setMediaUri(null);
        mHistory.setVodFlag(flag != null ? flag.getFlag() : "");
        mHistory.setVodRemarks(item.getName());
        mHistory.setEpisodeUrl(item.getUrl());
        EpisodeShortName.shorten(item.getName(), getVodName());
    }

    private boolean retryHistoryFallback(Map<String, String> currentHeaders) {
        if (currentHeaders != null && !currentHeaders.isEmpty()) {
            mPlaybackSession.updateFallbackHeaders(stripOpenDriveHints(currentHeaders));
        }
        HistoryPlaybackFallback.Attempt attempt = mPlaybackSession.consumeFallback();
        if (attempt == null) {
            OpenDriveLog.historyFallbackUnavailable();
            return false;
        }
        OpenDriveLog.historyFallbackTriggered(attempt.flag(), attempt.rawUrl());
        if (mHistory != null) mHistory.setMediaUri(null);
        mPlaybackSession.setPendingFallbackHeaders(attempt.headers());
        mViewModel.playerContent(getKey(), attempt.flag(), attempt.rawUrl());
        showProgress();
        return true;
    }

    private static Map<String, String> stripOpenDriveHints(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return Collections.emptyMap();
        Map<String, String> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() == null) continue;
            if (entry.getKey().regionMatches(true, 0, "X-OpenDrive-", 0, "X-OpenDrive-".length())) continue;
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private void clearHistoryFallback() {
        mPlaybackSession.clearFallback();
    }

    protected void toggleKeep() {
        if (mHistory == null) return;
        String key = getHistoryKey();
        Keep candidate = new Keep();
        candidate.setKey(key);
        candidate.setCid(VodConfig.getCid());
        candidate.setVodPic(mHistory.getVodPic());
        candidate.setVodName(mHistory.getVodName());
        candidate.setSiteName(getSite().getName());
        candidate.setCreateTime(System.currentTimeMillis());
        mFavorites.toggle(candidate);
    }

    protected void checkKeepImg() {
        mFavorites.check(getHistoryKey());
    }

    protected void updateKeep() {
        History history = mHistory;
        if (history != null) {
            mFavorites.update(getHistoryKey(), history.getVodName(), history.getVodPic());
        }
    }

    protected void updateVod(Vod item) {
        if (mHistory == null) return;
        boolean id = !item.getId().isEmpty();
        boolean pic = !item.getPic().isEmpty();
        boolean name = !item.getName().isEmpty();
        if (id) getIntent().putExtra("id", item.getId());
        if (id) UserLibrary.get().replaceHistoryKey(mHistory, getHistoryKey());
        if (name) mHistory.setVodName(item.getName());
        if (name) mBinding.name.setText(item.getName());
        Flag flag = getFlag();
        if (flag != null) updateFlag(flag, item.getFlags());
        if (pic) setArtwork(item.getPic());
        if (pic || name) setMetadata();
        if (pic || name) syncHistory();
        if (pic || name) updateKeep();
        setText(item);
    }

    protected void updateFlag(Flag activated, List<Flag> items) {
        boolean revSort = mHistory != null && mHistory.isRevSort();
        items.forEach(item -> mFlagAdapter.getItems().stream()
                .filter(item::equals).findFirst().ifPresentOrElse(target -> {
                    List<Episode> episodes = item.getEpisodes();
                    if (episodes != null) target.mergeEpisodes(episodes, revSort);
                    List<Episode> targetEpisodes = target.getEpisodes();
                    if (target.equals(activated)) setEpisodeAdapter(targetEpisodes != null ? targetEpisodes : java.util.Collections.emptyList());
                }, () -> mFlagAdapter.add(item)));
    }

    // Shared artwork logic

    protected void setArtwork(String url) {
        if (mHistory != null) mHistory.setVodPic(url);
        setArtwork();
    }

    protected void setArtwork() {
        if (mHistory == null) return;
        if (mArtworkTarget != null) {
            ImgUtil.clear(this, mArtworkTarget);
            mArtworkTarget = null;
        }
        mArtworkTarget = new CustomTarget<Drawable>() {
            @Override
            public void onResourceReady(@NonNull Drawable resource, @Nullable Transition<? super Drawable> transition) {
                if (mBinding != null) mBinding.exo.setDefaultArtwork(resource);
            }

            @Override
            public void onLoadCleared(@Nullable Drawable placeholder) {
                if (mBinding != null) mBinding.exo.setDefaultArtwork(placeholder);
            }

            @Override
            public void onLoadFailed(@Nullable Drawable errorDrawable) {
                if (mBinding != null) mBinding.exo.setDefaultArtwork(errorDrawable);
            }
        };
        ImgUtil.load(this, mHistory.getVodPic(), mArtworkTarget);
    }

    // Shared flag/search flow logic

    protected void checkFlag(Vod item) {
        boolean empty = !DetailPlaybackAvailability.hasPlayableEpisodes(item);
        mBinding.flag.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) {
            showError(getString(R.string.error_no_playable_episodes));
            // A broken provider can resolve a search ID to another title. Recover using the
            // selected title, rather than turning that unrelated detail into a new search.
            String requestedTitle = getName().isEmpty() ? item.getName() : getName();
            mBinding.name.setText(VodShortName.shorten(requestedTitle));
            initSearch(requestedTitle, getSite().isChangeable());
        } else {
            if (hasRealHistory && mHistory != null) {
                Flag historyFlag = mHistory.getFlag();
                Flag targetFlag = item.getFlags().stream()
                        .filter(f -> historyFlag != null && f.equals(historyFlag))
                        .findFirst()
                        .orElse(item.getFlags().get(0));
                onItemClick(targetFlag);
                if (mHistory.isRevSort()) reverseEpisode(true);
            } else {
                onItemClick(item.getFlags().get(0));
            }
        }
    }

    protected void startFlow() {
        if (!getSite().isChangeable()) return;
        if (isUseParse()) checkParse();
        else checkFlag();
    }

    protected void checkParse() {
        int position = mParseAdapter.getPosition();
        boolean last = position == mParseAdapter.getItemCount() - 1;
        boolean pass = position == 0 || last;
        if (last) initParse();
        if (pass) checkFlag();
        else nextParse(position);
    }

    protected void initParse() {
        if (mParseAdapter.isEmpty()) return;
        setParse(mParseAdapter.first());
    }

    protected void checkFlag() {
        int position = isGone(mBinding.flag) ? -1 : mFlagAdapter.getPosition();
        if (position == -1 || position == mFlagAdapter.getItemCount() - 1) checkSearch(false);
        else nextFlag(position);
    }

    protected void checkSearch(boolean force) {
        if (mQuickAdapter.isEmpty()) initSearch(mBinding.name.getText().toString(), true);
        else if (isAutoMode() || force) nextSite();
    }

    protected void initSearch(String keyword, boolean auto) {
        setAutoMode(auto);
        setInitAuto(auto);
        startSearch(keyword);
    }

    protected boolean isPass(Site item) {
        if (isAutoMode() && !item.isChangeable()) return false;
        return item.isSearchable();
    }

    protected void startSearch(String keyword) {
        mQuickAdapter.clear();
        List<Site> sites = new ArrayList<>();
        for (Site item : VodConfig.get().getSites()) if (isPass(item)) sites.add(item);
        mViewModel.searchContent(sites, keyword, true);
    }

    protected void setSearch(Result result) {
        List<Vod> items = result.getList();
        items.removeIf(this::mismatch);
        mQuickAdapter.addAll(items);
        mBinding.quick.setVisibility(View.VISIBLE);
        if (isInitAuto()) nextSite();
        if (items.isEmpty()) return;
        App.removeCallbacks(mShowEmptyRunnable);
    }

    protected boolean mismatch(Vod item) {
        if (getId().equals(item.getId())) return true;
        if (mBroken.contains(item.getId())) return true;
        String keyword = mBinding.name.getText().toString();
        if (isAutoMode()) return !item.getName().equals(keyword);
        else return !item.getName().contains(keyword);
    }

    protected void nextParse(int position) {
        Parse parse = mParseAdapter.get(position + 1);
        Notify.show(getString(R.string.play_switch_parse, parse.getName()));
        onItemClick(parse);
    }

    protected void nextFlag(int position) {
        Flag flag = mFlagAdapter.get(position + 1);
        Notify.show(getString(R.string.play_switch_flag, flag.getFlag()));
        onItemClick(flag);
    }

    protected void nextSite() {
        if (mQuickAdapter.isEmpty()) return;
        Vod item = mQuickAdapter.get(0);
        Notify.show(getString(R.string.play_switch_site, item.getSiteName()));
        mQuickAdapter.remove(0);
        if (mBroken.size() >= 50) mBroken.clear();
        mBroken.add(getId());
        setInitAuto(false);
        getDetail(item);
    }

    // Shared player control

    protected void onPaused() {
        if (controller() != null) controller().pause();
    }

    protected void onPlay() {
        PlayerManager p = player();
        MediaController c = controller();
        if (c == null || p == null) return;
        if (mHistory != null && isEnded()) c.seekTo(mHistory.getOpening());
        if (!p.isEmpty() && isIdle()) c.prepare();
        c.play();
    }

    protected void onReplay() {
        if (mHistory != null) mHistory.setPosition(C.TIME_UNSET);
        PlayerManager p = player();
        if (p == null || p.isEmpty()) onRefresh();
        else p.setMediaItem();
    }

    protected void onRefresh() {
        saveHistory();
        PlayerManager p = player();
        if (p != null) {
            p.stop();
            p.clear();
        }
        mClock.setCallback(null);
        if (mFlagAdapter.isEmpty()) {
            onError(getString(R.string.error_play_url));
            return;
        }
        if (mEpisodeAdapter.isEmpty()) {
            onError(getString(R.string.error_play_url));
            return;
        }
        if (mEpisodeAdapter.getPosition() == -1) return;
        getPlayer(getFlag(), getEpisode());
    }

    protected void checkEnded(boolean notify) {
        if (mEpisodeAdapter == null) return;
        if (mBinding.control.action.loop.isActivated()) {
            onReplay();
        } else {
            checkNext(notify);
        }
    }

    protected void checkNext(boolean notify) {
        Episode item = mEpisodeAdapter.getNext();
        if (item == null) {
            if (notify) Notify.show(R.string.error_play_next);
        } else if (!item.isActivated()) {
            onItemClick(item);
        } else if (notify) {
            Notify.show(R.string.error_play_next);
        }
    }

    protected void checkPrev() {
        Episode item = mEpisodeAdapter.getPrev();
        if (item == null) {
            Notify.show(R.string.error_play_prev);
        } else if (!item.isActivated()) {
            onItemClick(item);
        } else {
            // Previous episode is already the current one — replay it from start
            if (mHistory != null) mHistory.setPosition(C.TIME_UNSET);
            onRefresh();
        }
    }

    protected void setOpening(long opening) {
        if (mHistory == null) return;
        mHistory.setOpening(opening);
        mBinding.control.action.opening.setText(opening <= 0 ? getString(R.string.play_op) : Util.timeMs(mHistory.getOpening()));
    }

    protected void setEnding(long ending) {
        if (mHistory == null) return;
        mHistory.setEnding(ending);
        mBinding.control.action.ending.setText(ending <= 0 ? getString(R.string.play_ed) : Util.timeMs(mHistory.getEnding()));
    }

    // Shared track/metadata logic

    protected void setTrackVisible() {
        PlayerManager p = player();
        if (p == null) return;
        mBinding.control.action.subtitleTracks.setVisibility(p.haveTrack(C.TRACK_TYPE_TEXT) || p.isVod() ? View.VISIBLE : View.GONE);
        mBinding.control.action.audio.setVisibility(p.haveTrack(C.TRACK_TYPE_AUDIO) ? View.VISIBLE : View.GONE);
        mBinding.control.action.videoTracks.setVisibility(p.haveTrack(C.TRACK_TYPE_VIDEO) ? View.VISIBLE : View.GONE);
    }

    protected void setTitleVisible() {
        mBinding.control.action.actionTitle.setVisibility(View.GONE);
    }

    protected MediaMetadata buildMetadata() {
        String title = mHistory != null ? mHistory.getVodName() : "";
        String episode = getEpisode().getName();
        boolean empty = episode.isEmpty() || title.equals(episode);
        String artist = empty ? "" : episode;
        String pic = mHistory != null ? mHistory.getVodPic() : "";
        return PlayerManager.buildMetadata(title, artist, pic);
    }

    protected void setMetadata() {
        PlayerManager p = player();
        if (p != null) p.setMetadata(buildMetadata());
    }

    protected void setPosition() {
        PlayerManager p = player();
        if (p == null) return;
        if (mHistory != null) {
            p.seekTo(Math.max(mHistory.getOpening(), mHistory.getPosition()));
            return;
        }
        long castPosition = getIntent().getLongExtra("castPosition", C.TIME_UNSET);
        if (castPosition > 0L) p.seekTo(castPosition);
        getIntent().removeExtra("castPosition");
    }

    // Shared traffic

    protected void setTraffic() {
        Traffic.setSpeed(mBinding.progress.traffic);
    }

    // Shared ViewModel setup

    protected void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getResult().observe(this, mObserveDetail);
        mViewModel.getPlayer().observe(this, mObservePlayer);
        mViewModel.getSearch().observe(this, mObserveSearch);
    }

    // Shared NavigationCallback

    private static class NavigationCallbackImpl implements PlaybackService.NavigationCallback {
        private final WeakReference<BaseVideoActivity> ref;

        NavigationCallbackImpl(BaseVideoActivity activity) {
            this.ref = new WeakReference<>(activity);
        }

        @Override
        public void onNext() {
            BaseVideoActivity activity = ref.get();
            if (activity != null) activity.checkNext(true);
        }

        @Override
        public void onPrev() {
            BaseVideoActivity activity = ref.get();
            if (activity != null) activity.checkPrev();
        }

        @Override
        public void onStop() {
            BaseVideoActivity activity = ref.get();
            if (activity != null) activity.finish();
        }

        @Override
        public void onLoop() {
            BaseVideoActivity activity = ref.get();
            if (activity != null) activity.onLoop();
        }

        @Override
        public void onReplay() {
            BaseVideoActivity activity = ref.get();
            if (activity != null) activity.onReplay();
        }
    }

    protected final PlaybackService.NavigationCallback mNavigationCallback = new NavigationCallbackImpl(this);

    protected void onLoop() {
        mBinding.control.action.loop.setActivated(!mBinding.control.action.loop.isActivated());
    }

    // Shared PlaybackActivity callbacks

    @Override
    protected String getPlaybackKey() {
        return getHistoryKey();
    }

    @Override
    protected void onPrepare() {
        // History timing must not depend on a one-shot track callback, which
        // may have fired before a newly bound activity registered its listener.
        if (mClock != null) mClock.setCallback(this);
        setDecode();
        setPosition();
    }

    @Override
    protected void onTracksChanged() {
        setTrackVisible();
        mClock.setCallback(this);
    }

    @Override
    protected void onTitlesChanged() {
        setTitleVisible();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onCastEvent(CastEvent event) {
        if (isRedirect()) return;
        ReceiveDialog.create().event(event).show(this);
    }

    protected void onDanmaku() {
        DanmakuDialog.create().player(player()).show(this);
        hideControl();
    }

    protected void onReset() {
        if (isReplay()) onReplay();
        else onRefresh();
    }

    protected void onTrack(View view) {
        try {
            TrackDialog.create().type(Integer.parseInt(view.getTag().toString())).player(player()).show(this);
        } catch (NumberFormatException ignored) {
        }
        hideControl();
    }

    protected void setR1Callback() {
        App.post(mHideControlRunnable, Constant.INTERVAL_HIDE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && requestCode == 1001) PlayerHelper.onExternalResult(data, service()::dispatchNext, controller()::seekTo);
    }

    @Override
    protected void onError(String msg) {
        if (!isUiActive()) return;
        PlayerManager currentPlayer = player();
        if (currentPlayer == null) return;
        Track.delete(currentPlayer.getKey());
        mClock.setCallback(null);
        currentPlayer.resetTrack();
        currentPlayer.reset();
        currentPlayer.stop();
        if (PlaybackErrorRouter.isQuarkSmartQuality(msg)) {
            releaseQrResources();
            clearHistoryFallback();
            showError(getString(R.string.error_quark_smart_quality_unavailable));
            return;
        }
        int cloudError = com.wuming.android.tv.cloud.opendrive.OpenDriveMessages.failure(msg);
        if (cloudError != 0) msg = getString(cloudError);
        if (PlaybackErrorRouter.isUnsupportedFormat(msg, getString(R.string.error_play_video_unsupported))) {
            releaseQrResources();
            showError(msg);
            return;
        }
        if (retryHistoryFallback(null)) return;
        PlaybackErrorRouter router = PlaybackErrorRouter.classify(msg, isBilibiliLoginError(msg));
        switch (router.kind()) {
            case BILIBILI_LOGIN -> onBilibiliLogin();
            case QR_CHALLENGE -> mLogin.show(router.challenge());
            case GENERIC -> {
                releaseQrResources();
                showError(router.displayMessage());
                startFlow();
            }
            default -> throw new IllegalStateException("Unexpected error kind: " + router.kind());
        }
    }

    protected final void onBilibiliLogin() {
        mLogin.showBilibiliLogin();
    }

    protected final boolean isBilibiliSite() {
        return PlaybackLoginFlow.isBilibiliSite(getSite());
    }

    private boolean isBilibiliLoginError(String message) {
        return PlaybackLoginFlow.isBilibiliLoginError(message, getSite());
    }



    private boolean isBaiduLogin() {
        return DriveLoginHelper.isBaiduLogin(getEpisode(), getFlag());
    }

    private void releaseQrResources() {
        mLogin.release();
    }

    private boolean isUiActive() {
        return mBinding != null && !isFinishing() && !isDestroyed();
    }

    @Override
    protected void onReclaim() {
        Result result = mViewModel.getPlayer().getValue();
        if (result != null) setPlayer(result);
    }

    @Override
    protected void onStateChanged(int state) {
        switch (state) {
            case Player.STATE_BUFFERING:
                showProgress();
                break;
            case Player.STATE_READY:
                clearHistoryFallback();
                hideProgress();
                PlayerManager readyPlayer = player();
                if (readyPlayer != null) readyPlayer.reset();
                break;
            case Player.STATE_ENDED:
                mPlaybackSession.finish(System.currentTimeMillis(), true);
                checkEnded(true);
                break;
        }
    }

    @Override
    public void onTimeChanged(long time) {
        if (!isOwner() || mHistory == null) return;
        com.wuming.tv.core.playback.PlaybackSessionProgress.Tick tick = mPlaybackSession.update(
                mHistory, mCurrentVod, player().isPlaying(), player().getPosition(), player().getDuration(),
                SystemClock.elapsedRealtime(), time);
        if (mHistory.canSave() && mHistory.canSync()) syncHistory();
        if (tick.getSkipEnding()) checkEnded(false);
        else if (tick.getPrewarmNext()) prewarmNextEpisode();
    }

    private void prewarmNextEpisode() {
        if (mEpisodeAdapter == null || mBinding.control.action.loop.isActivated()) return;
        Episode next = mEpisodeAdapter.getNext();
        if (next == null || next.isActivated()) return;
        Flag flag = getFlag();
        if (flag == null) return;
        String episodeUrl = next.getUrl();
        if (isBilibiliSite()) {
            boolean loggedIn = com.github.catvod.net.PersistentCookieJar.get()
                    .hasLoginCookies("https://www.bilibili.com/");
            episodeUrl = Bilibili.normalizeEpisodeUrl(episodeUrl, loggedIn);
        }
        Episode playbackEpisode = Episode.create(next.getName(), episodeUrl);
        String playbackId = PlaybackRoutePolicy.select(AppSettings.getLinkRoute(), mHistory,
                flag.getFlag(), playbackEpisode);
        if (!OpenDriveUri.isValid(playbackId)) playbackId = episodeUrl;
        mViewModel.prewarmPlayerContent(getKey(), flag.getFlag(), playbackId);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (isRedirect()) return;
        if (event.getType() == RefreshEvent.Type.DETAIL) getDetail();
        else if (event.getType() == RefreshEvent.Type.PLAYER) onRefresh();
        else if (event.getType() == RefreshEvent.Type.VOD) {
            Vod vod = event.getVod();
            if (vod != null) updateVod(vod);
        } else if (event.getType() == RefreshEvent.Type.SUBTITLE) {
            PlayerManager p = player();
            if (p != null) p.setSub(Sub.from(event.getPath()));
        } else if (event.getType() == RefreshEvent.Type.DANMAKU) {
            PlayerManager p = player();
            if (p != null) p.setDanmaku(Danmaku.from(event.getPath()));
        }
    }

    // Shared item click handlers (subclasses override for variant-specific behavior)

    public void onItemClick(Flag item) {
        if (item.isActivated()) return;
        mFlagAdapter.setActivated(item);
        List<Episode> episodes = item.getEpisodes();
        setEpisodeAdapter(episodes != null ? episodes : java.util.Collections.emptyList());
        seamless(item);
    }

    protected void seamless(Flag flag) {
        Episode episode;
        List<Episode> episodes = flag.getEpisodes();
        if (episodes == null) episodes = java.util.Collections.emptyList();

        if (hasRealHistory) {
            episode = flag.find(mHistory.getVodRemarks(), getMark().isEmpty());
        } else {
            setQualityVisible(false);
            final Episode firstEpisode = episodes.isEmpty() ? null : episodes.get(0);
            if (firstEpisode == null) {
                hideProgress();
                return;
            }
            hideProgress();
            scheduleAutoPlay(firstEpisode);
            return;
        }

        setQualityVisible(episode != null && episode.isActivated() && mQualityAdapter.getItemCount() > 1);
        if (episode == null) return;
        if (episode.isActivated()) return;
        if (mHistory != null) mHistory.setVodRemarks(episode.getName());
        onItemClick(episode);
    }

    private void scheduleAutoPlay(Episode episode) {
        cancelAutoPlay();
        mAutoPlayFirstEpisodeRunnable = new AutoPlayRunnable(this, episode);
        App.post(mAutoPlayFirstEpisodeRunnable, 2000);
    }

    private void cancelAutoPlay() {
        App.removeCallbacks(mAutoPlayFirstEpisodeRunnable);
        mAutoPlayFirstEpisodeRunnable = null;
    }

    public void onItemClick(Episode item) {
        cancelAutoPlay();
        mFlagAdapter.toggle(item);
        if (shouldEnterFullscreen(item)) return;
        if (isFullscreen()) Notify.show(getString(R.string.play_ready, item.getName()));
        onRefresh();
    }

    public void onItemClick(Result result) {
        startPlayer(getHistoryKey(), result, isUseParse(), getSite().getTimeout(), buildMetadata());
    }

    public void onItemClick(Vod item) {
        setAutoMode(false);
        getDetail(item);
    }

    public void onItemClick(Parse item) {
        setParse(item);
        onRefresh();
    }

    // Shared destroy

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseVideoResources();
    }

    protected void releaseVideoResources() {
        mFavorites.close();
        if (mNetworkObserver != null) mNetworkObserver.close();
        mHistoryLoader.close();
        releaseQrResources();
        cancelAutoPlay();
        if (mClock != null) {
            mClock.release();
            mClock = null;
        }
        if (mVideoClock != null) {
            mVideoClock.release();
            mVideoClock = null;
        }
        if (mArtworkTarget != null) {
            ImgUtil.clear(this, mArtworkTarget);
            mArtworkTarget = null;
        }
        saveHistory(true);
        mPlaybackSession.finish(System.currentTimeMillis(), false);
        RefreshEvent.keep();
        App.removeCallbacks(mHideControlRunnable, mShowEmptyRunnable);
        if (mViewModel != null) {
            mViewModel.stopSearch();
            mViewModel.getResult().removeObservers(this);
            mViewModel.getPlayer().removeObservers(this);
            mViewModel.getSearch().removeObservers(this);
            mViewModel = null;
        }
        mBinding = null;
        mEpisodeAdapter = null;
        mQualityAdapter = null;
        mQuickAdapter = null;
        mParseAdapter = null;
        mFlagAdapter = null;
        if (mBroken != null) mBroken.clear();
        mBroken = null;
        clearHistoryFallback();
        mHistory = null;
        mCurrentVod = null;
        mPlaybackSession.release(System.currentTimeMillis());
        mDetailLoadCoordinator.discardPending();
        mHideControlRunnable = null;
        mShowEmptyRunnable = null;
        mAutoPlayFirstEpisodeRunnable = null;
        mFrameParams = null;
        mObserveDetail = null;
        mObservePlayer = null;
        mObserveSearch = null;
    }
}
