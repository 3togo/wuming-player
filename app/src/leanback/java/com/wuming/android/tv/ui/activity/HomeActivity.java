package com.wuming.android.tv.ui.activity;

import com.wuming.android.tv.utils.TextScale;

import android.annotation.SuppressLint;
import android.app.SearchManager;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.splashscreen.SplashScreen;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.FocusHighlight;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.OnChildViewHolderSelectedListener;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.wuming.android.tv.LeanbackProduct;
import com.wuming.android.tv.R;
import com.wuming.android.tv.Updater;
import com.wuming.android.tv.api.config.LiveConfig;
import com.wuming.android.tv.api.config.VodConfig;
import com.wuming.android.tv.api.config.ConfigurationStartup;
import com.wuming.android.tv.bean.FilterCache;
import com.wuming.android.tv.bean.Config;
import com.wuming.android.tv.bean.Func;
import com.wuming.android.tv.bean.History;
import com.wuming.android.tv.bean.VodShortName;
import com.wuming.android.tv.bean.Result;
import com.wuming.android.tv.bean.Site;
import com.wuming.android.tv.bean.Style;
import com.wuming.android.tv.bean.Vod;
import com.wuming.android.tv.cloud.opendrive.OpenDriveShareTarget;
import com.wuming.android.tv.databinding.ActivityHomeBinding;
import com.wuming.android.tv.db.AppDatabase;
import com.wuming.android.tv.event.CastEvent;
import com.wuming.android.tv.event.ConfigEvent;
import com.wuming.android.tv.event.RefreshEvent;
import com.wuming.android.tv.library.UserLibrary;
import com.wuming.android.tv.event.ServerEvent;
import com.wuming.android.tv.impl.Callback;
import com.wuming.android.tv.model.SiteViewModel;
import com.wuming.android.tv.player.Source;
import com.wuming.android.tv.recommendation.RecommendationRequest;
import com.wuming.android.tv.server.Server;
import com.wuming.android.tv.service.DLNARendererService;
import com.wuming.android.tv.service.PlaybackService;
import com.wuming.android.tv.setting.AppSettings;
import com.wuming.android.tv.ui.adapter.BaseDiffCallback;
import com.wuming.android.tv.ui.base.BaseActivity;
import com.wuming.android.tv.ui.custom.CustomRowPresenter;
import com.wuming.android.tv.ui.custom.CustomSelector;
import com.wuming.android.tv.ui.custom.CustomTitleView;
import com.wuming.android.tv.ui.dialog.LeanbackActionDialog;
import com.wuming.android.tv.ui.dialog.LeanbackSiteDialog;
import com.wuming.android.tv.ui.presenter.FuncPresenter;
import com.wuming.android.tv.ui.presenter.HeaderPresenter;
import com.wuming.android.tv.ui.presenter.HistoryPresenter;
import com.wuming.android.tv.ui.presenter.ProgressPresenter;
import com.wuming.android.tv.ui.presenter.VodPresenter;
import com.wuming.android.tv.utils.Clock;
import com.wuming.android.tv.utils.DownloadedCatVod;
import com.wuming.android.tv.utils.FileChooser;
import com.wuming.android.tv.utils.ImgUtil;
import com.wuming.android.tv.utils.KeyUtil;
import com.wuming.android.tv.utils.LayoutConfig;
import com.wuming.android.tv.utils.Notify;
import com.wuming.android.tv.utils.PermissionUtil;
import com.wuming.android.tv.utils.ResUtil;
import com.wuming.android.tv.utils.Task;
import com.wuming.android.tv.utils.UrlUtil;
import com.github.catvod.net.OkHttp;
import com.google.common.collect.Lists;
import com.wuming.tv.core.media.PrimaryMediaAvailability;
import com.wuming.tv.core.media.TvHomeAction;
import com.wuming.tv.core.media.TvHomeLayoutSpec;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class HomeActivity extends BaseActivity implements CustomTitleView.Listener, VodPresenter.OnClickListener, FuncPresenter.OnClickListener, HistoryPresenter.OnClickListener {

    private final RecommendationRequest mRecommendations = new RecommendationRequest();
    private ActivityHomeBinding mBinding;
    private ArrayObjectAdapter mHistoryAdapter;
    private ArrayObjectAdapter mFuncAdapter;
    private ArrayObjectAdapter mAdapter;
    private HistoryPresenter mPresenter;
    private SiteViewModel mViewModel;
    private Result mResult;
    private Clock mClock;
    private boolean mOpenTypesAfterSiteChange;
    private volatile Result prefetchedResult;
    private volatile String prefetchedHomeKey;

    private Site getHome() {
        return VodConfig.get().getHome();
    }

    private Config getConfig() {
        return VodConfig.get().getConfig();
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityHomeBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        checkAction(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mResult = Result.empty();
        mClock = Clock.create(mBinding.clock);
        ViewGroup.LayoutParams toolbarParams = mBinding.toolbar.getLayoutParams();
        toolbarParams.height = LayoutConfig.getTvHomeToolbarHeightPixels();
        mBinding.toolbar.setLayoutParams(toolbarParams);
        int toolbarHeight = toolbarParams.height;
        int iconSize = LayoutConfig.getTvHomeToolbarIconSizePixels(toolbarHeight);
        ViewGroup.LayoutParams logoParams = mBinding.logo.getLayoutParams();
        logoParams.width = iconSize;
        logoParams.height = iconSize;
        mBinding.logo.setLayoutParams(logoParams);

        TextScale.bind(mBinding.title);
        TextScale.bind(mBinding.clock);
        mBinding.progressLayout.showProgress();
        setRecyclerView();
        setViewModel();
        setAdapter();
        PermissionUtil.requestLocalNetwork(this, granted -> {
            if (granted) Task.execute(() -> DLNARendererService.start(this));
            initConfig();
        });
    }

    @Override
    protected void initEvent() {
        mBinding.title.setListener(this);
        mBinding.recycler.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                mBinding.toolbar.setVisibility(position == 0 ? View.VISIBLE : View.INVISIBLE);
                if (mPresenter.isDelete()) setHistoryDelete(false);
            }
        });
    }

    @Override
    protected void onPostCreate() {
        Updater.create().start(this);
    }

    private void checkAction(Intent intent) {
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            push(intent.getStringExtra(Intent.EXTRA_TEXT));
        } else if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            if (!openOpenDriveShare(intent.getData().toString())) {
                PermissionUtil.requestFile(this, allGranted -> checkType(intent));
            }
        } else if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            String keyword = intent.getStringExtra(SearchManager.QUERY);
            if (!TextUtils.isEmpty(keyword)) LeanbackSearchActivity.start(this, keyword);
        }
    }

    private void checkType(Intent intent) {
        if ("text/plain".equals(intent.getType()) || UrlUtil.path(intent.getData()).endsWith(".m3u")) {
            FileChooser.importFile(this, intent.getData(), path -> loadLive("file:/" + path));
        } else {
            push(intent.getData().toString());
        }
    }

    private void push(String text) {
        if (TextUtils.isEmpty(text)) return;
        if (!openOpenDriveShare(text)) VideoActivity.push(this, text);
    }

    private boolean openOpenDriveShare(String text) {
        Optional<OpenDriveShareTarget> share = OpenDriveShareTarget.fromText(text);
        if (share.isEmpty()) return false;
        if (AppSettings.getLinkRoute() == AppSettings.LINK_ROUTE_BUILTIN) {
            VideoActivity.start(this, VodConfig.OPEN_DRIVE_KEY, share.get().itemId(),
                    getString(R.string.open_drive_share_title), null);
        } else {
            Site site = DownloadedCatVod.findCloudSite();
            if (site == null) {
                Notify.show(R.string.link_route_downloaded_missing);
                return true;
            }
            VideoActivity.start(this, site.getKey(), share.get().shareUrl(), site.getName(), null);
        }
        return true;
    }

    @SuppressLint("RestrictedApi")
    private void setRecyclerView() {
        CustomSelector selector = new CustomSelector();
        selector.addPresenter(Integer.class, new HeaderPresenter());
        selector.addPresenter(String.class, new ProgressPresenter());
        selector.addPresenter(Vod.class, new VodPresenter(this, Style.list()));
        selector.addPresenter(ListRow.class,
                new CustomRowPresenter(R.dimen.vs_16), VodPresenter.class);
        selector.addPresenter(ListRow.class,
                new CustomRowPresenter(R.dimen.vs_16), FuncPresenter.class);
        selector.addPresenter(ListRow.class,
                new CustomRowPresenter(R.dimen.vs_16, FocusHighlight.ZOOM_FACTOR_SMALL,
                        HorizontalGridView.FOCUS_SCROLL_ALIGNED), HistoryPresenter.class);
        mBinding.recycler.setAdapter(new ItemBridgeAdapter(mAdapter = new ArrayObjectAdapter(selector)));
        mBinding.recycler.setVerticalSpacing(ResUtil.getDimensionPixelSize(R.dimen.vs_16));
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getResult().observe(this, result -> {
            mAdapter.remove("progress");
            mResult = result;
            mRecommendations.rank(this, result.getList(), getHome().getKey(),
                    () -> getHome().getKey(), ranked -> {
                if (mAdapter == null) return;
                result.setList(ranked);
                addVideo(result);
                FilterCache.clear().put(result);
                openTypesAfterSiteChange(result);
            });
        });
    }

    private void openTypesAfterSiteChange(Result result) {
        if (!mOpenTypesAfterSiteChange) return;
        mOpenTypesAfterSiteChange = false;
        // Mobile renders provider categories directly on its home fragment. Leanback
        // keeps them in VodActivity, so category-only providers (for example 我的云盘)
        // must be opened here or selecting them appears to do nothing.
        if (result.getList().isEmpty() && !result.getTypes().isEmpty()) VodActivity.start(this, result);
    }

    private void setAdapter() {
        mHistoryAdapter = new ArrayObjectAdapter(mPresenter = new HistoryPresenter(this));
        mAdapter.add(new ListRow(mFuncAdapter = new ArrayObjectAdapter(new FuncPresenter(this))));
        mAdapter.add(R.string.home_history);
        mAdapter.add(R.string.home_recommend);
    }

    private void setTitle() {
        List<String> items = Arrays.asList(getHome().getName(), getConfig().getName(), getString(R.string.app_name));
        Optional<String> optional = items.stream().filter(s -> !TextUtils.isEmpty(s)).findFirst();
        optional.ifPresent(s -> mBinding.title.setText(s));
    }

    private void initConfig() {
        if (!VodConfig.get().getSites().isEmpty()) {
            Site homeSite = VodConfig.get().getHome();
            if (!homeSite.getKey().isEmpty()) {
                prefetchedHomeKey = homeSite.getKey();
                Task.execute(() -> {
                    try {
                        prefetchedResult = com.wuming.android.tv.api.SiteApi.homeContent(homeSite);
                    } catch (Exception e) {
                        prefetchedResult = null;
                    }
                });
            }
        }
        ConfigurationStartup.load(getCallback());
    }

    private Callback getCallback() {
        return lifecycleCallback(new Callback() {
            @Override
            public void success() {
                showContent();
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
                showContent();
            }
        });
    }

    private void showContent() {
        mBinding.progressLayout.showContent();
        setTitle();
        setLogo();
        checkAction(getIntent());
        setFocus();
    }

    private void loadLive(String url) {
        Task.execute(() -> {
            Config config = Config.find(url, 1);
            runOnUiThread(() -> LiveConfig.load(config, lifecycleCallback(new Callback() {
                @Override
                public void success() {
                    LeanbackLiveActivity.start(getActivity());
                }
            })));
        });
    }

    private void setFocus() {
        mBinding.title.setSelected(true);
        postUi(() -> mBinding.title.setFocusable(true), 500);
        if (!mBinding.title.hasFocus()) mBinding.recycler.requestFocus();
    }

    private void getVideo() {
        mRecommendations.invalidate();
        mResult = Result.empty();
        int index = getRecommendIndex();
        boolean gone = mAdapter.indexOf("progress") == -1;
        boolean hasItem = gone && mAdapter.size() > index;
        if (hasItem) mAdapter.removeItems(index, mAdapter.size() - index);
        if (gone) mAdapter.add("progress");
        if (prefetchedResult != null && prefetchedHomeKey != null
                && prefetchedHomeKey.equals(getHome().getKey())) {
            Result result = prefetchedResult;
            prefetchedResult = null;
            prefetchedHomeKey = null;
            mAdapter.remove("progress");
            mResult = result;
            mRecommendations.rank(this, result.getList(), getHome().getKey(),
                    () -> getHome().getKey(), ranked -> {
                if (mAdapter == null) return;
                result.setList(ranked);
                addVideo(result);
                FilterCache.clear().put(result);
                openTypesAfterSiteChange(result);
            });
        } else {
            prefetchedResult = null;
            prefetchedHomeKey = null;
            mViewModel.homeContent();
        }
    }

    private void addVideo(Result result) {
        Style style = result.getStyle(getHome().getStyle());
        if (style.isList()) mAdapter.addAll(mAdapter.size(), result.getList());
        else addGrid(result.getList(), style);
    }

    private void addGrid(List<Vod> items, Style style) {
        List<ListRow> rows = new ArrayList<>();
        VodPresenter presenter = new VodPresenter(this, style);
        for (List<Vod> part : Lists.partition(items, LeanbackProduct.getColumn(style))) {
            ArrayObjectAdapter adapter = new ArrayObjectAdapter(presenter);
            adapter.addAll(0, part);
            rows.add(new ListRow(adapter));
        }
        mAdapter.addAll(mAdapter.size(), rows);
    }

    private void setFunc() {
        List<Func> items = new ArrayList<>();
        PrimaryMediaAvailability media = new PrimaryMediaAvailability(true, LiveConfig.hasUrl());
        for (TvHomeAction action : TvHomeLayoutSpec.Companion.leanback(media).getActions()) {
            int resId = getFuncResId(action);
            if (resId != 0) items.add(Func.create(resId));
            if (action == TvHomeAction.KEEP) items.add(Func.create(R.string.home_playlist));
        }
        mFuncAdapter.setItems(items, new BaseDiffCallback<Func>());
    }

    private int getFuncResId(TvHomeAction action) {
        return switch (action) {
            case VOD -> R.string.home_vod;
            case LIVE_TV -> R.string.home_live;
            case SEARCH -> R.string.home_search;
            case KEEP -> R.string.home_keep;
            case PUSH -> R.string.home_push;
            case SETTINGS -> R.string.home_setting;
            default -> 0;
        };
    }

    private void getHistory() {
        getHistory(false);
    }

    private void getHistory(boolean renew) {
        Task.execute(() -> {
            List<History> items = UserLibrary.get().histories();
            runOnUiThread(() -> {
                int historyIndex = getHistoryIndex();
                int recommendIndex = getRecommendIndex();
                boolean exist = recommendIndex - historyIndex == 2;
                if (renew) mHistoryAdapter = new ArrayObjectAdapter(mPresenter = new HistoryPresenter(this));
                if ((items.isEmpty() && exist) || (renew && exist)) mAdapter.removeItems(historyIndex, 1);
                if ((!items.isEmpty() && !exist) || (renew && exist)) mAdapter.add(historyIndex, new ListRow(mHistoryAdapter));
                mHistoryAdapter.setItems(items, new BaseDiffCallback<History>());
            });
        });
    }

    private void setHistoryDelete(boolean delete) {
        mPresenter.setDelete(delete);
        mHistoryAdapter.notifyArrayItemRangeChanged(0, mHistoryAdapter.size());
    }

    private void clearHistory() {
        mAdapter.removeItems(getHistoryIndex(), 1);
        Task.execute(() -> UserLibrary.get().deleteHistoryCatalog(VodConfig.getCid()));
        mPresenter.setDelete(false);
        mHistoryAdapter.clear();
    }

    private int getHistoryIndex() {
        return mAdapter.indexOf(R.string.home_history) + 1;
    }

    private int getRecommendIndex() {
        return mAdapter.indexOf(R.string.home_recommend) + 1;
    }

    private void setLogo() {
        ImgUtil.logo(mBinding.logo);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        switch (event.type()) {
            case VOD:
                RefreshEvent.history();
                RefreshEvent.home();
                setLogo();
                break;
            case COMMON:
                setFunc();
                break;
            case BOOT:
                LeanbackLiveActivity.start(this);
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        switch (event.getType()) {
            case HOME:
                getVideo();
                setTitle();
                break;
            case HISTORY:
                getHistory();
                break;
            case SIZE:
                getVideo();
                getHistory(true);
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onServerEvent(ServerEvent event) {
        switch (event.type()) {
            case SEARCH:
                LeanbackSearchActivity.start(this, event.text());
                break;
            case PUSH:
                push(event.text());
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onCastEvent(CastEvent event) {
        if (VodConfig.get().getConfig().equals(event.config())) {
            VideoActivity.cast(this, UserLibrary.get().saveHistory(
                    event.history(), VodConfig.getCid()), event.audioTrackFormat());
        } else {
            VodConfig.load(event.config(), getCallback(event));
        }
    }

    private Callback getCallback(CastEvent event) {
        return lifecycleCallback(new Callback() {
            @Override
            public void success() {
                onCastEvent(event);
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
            }
        });
    }

    @Override
    public void onItemClick(Func item) {
        if (item.getResId() == R.string.home_vod) VodActivity.start(this, mResult);
        else if (item.getResId() == R.string.home_live) LeanbackLiveActivity.start(this);
        else if (item.getResId() == R.string.home_keep) LeanbackKeepActivity.start(this);
        else if (item.getResId() == R.string.home_push) PushActivity.start(this);
        else if (item.getResId() == R.string.home_search) LeanbackSearchActivity.start(this);
        else if (item.getResId() == R.string.home_playlist) PlaylistActivity.start(this);
        else if (item.getResId() == R.string.home_setting) SettingActivity.start(this);
    }

    @Override
    public void onItemClick(Vod item) {
        if (item.isAction()) mViewModel.action(getHome().getKey(), item.getAction());
        else if (getHome().isIndex()) CollectActivity.start(this, item.getName());
        else VideoActivity.start(this, getHome().getKey(), item.getId(), item.getName(), item.getPic());
    }

    @Override
    public boolean onLongClick(Vod item) {
        if (item.isAction()) return false;
        CollectActivity.start(this, item.getName());
        return true;
    }

    @Override
    public void onItemClick(History item) {
        VideoActivity.start(this, item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
    }

    @Override
    public void onItemDelete(History item) {
        Task.execute(() -> UserLibrary.get().deleteHistory(item));
        mHistoryAdapter.remove(item);
        if (mHistoryAdapter.size() > 0) return;
        mAdapter.removeItems(getHistoryIndex(), 1);
        mPresenter.setDelete(false);
    }

    @Override
    public boolean onLongClick(History item) {
        if (mPresenter.isDelete()) {
            clearHistory();
            return true;
        }
        LeanbackActionDialog.show(this, VodShortName.lookup(item.getVodName()), R.array.history_actions, (dialog, which) -> {
                    if (which == 0) {
                        boolean added = com.wuming.android.tv.library.PlaylistStore.get().add(item);
                        Notify.show(added ? R.string.playlist_added : R.string.playlist_duplicate);
                    } else if (which == 1) {
                        setHistoryDelete(true);
                    }
                });
        return true;
    }

    @Override
    public void showDialog() {
        LeanbackSiteDialog.create(this).show();
    }

    @Override
    public void onRefresh() {
        getVideo();
    }

    @Override
    public void setSite(Site item) {
        mRecommendations.invalidate();
        mOpenTypesAfterSiteChange = true;
        VodConfig.get().setHome(item);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (KeyUtil.isMenuKey(event)) {
            showDialog();
            return true;
        }
        if (KeyUtil.isActionDown(event) && KeyUtil.isDownKey(event) && getCurrentFocus() == mBinding.title) {
            View first = mBinding.recycler.getChildAt(0);
            if (first != null) return first.requestFocus();
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mClock.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mClock.stop();
    }

    @Override
    protected void onBackInvoked() {
        if (mBinding.progressLayout.isProgress()) {
            showContent();
        } else if (mPresenter.isDelete()) {
            setHistoryDelete(false);
        } else if (mBinding.recycler.getSelectedPosition() != 0) {
            mBinding.recycler.scrollToPosition(0);
        } else {
            if (PlaybackService.isRunning()) moveTaskToBack(true);
            else super.onBackInvoked();
        }
    }

    @Override
    protected void onDestroy() {
        mRecommendations.invalidate();
        if (mClock != null) mClock.release();
        if (mBinding != null) mBinding.title.setListener(null);
        if (mViewModel != null) {
            mViewModel.getResult().removeObservers(this);
            mViewModel = null;
        }
        DLNARendererService.stop(this);
        AppDatabase.backup();
        OkHttp.get().clear();
        Source.get().exit();
        Server.get().stop();
        super.onDestroy();
    }
}
