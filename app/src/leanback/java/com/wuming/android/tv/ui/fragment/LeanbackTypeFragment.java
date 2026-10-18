package com.wuming.android.tv.ui.fragment;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.FocusHighlight;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.ListRow;
import androidx.lifecycle.ViewModelProvider;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.viewbinding.ViewBinding;

import com.wuming.android.tv.LeanbackProduct;
import com.wuming.android.tv.R;
import com.wuming.android.tv.api.config.VodConfig;
import com.wuming.android.tv.bean.FilterCache;
import com.wuming.android.tv.bean.Filter;
import com.wuming.android.tv.bean.Result;
import com.wuming.android.tv.bean.Site;
import com.wuming.android.tv.bean.Style;
import com.wuming.android.tv.bean.Value;
import com.wuming.android.tv.bean.Vod;
import com.wuming.android.tv.cloud.opendrive.OpenDriveLoginLauncher;
import com.wuming.android.tv.databinding.FragmentTypeBinding;
import com.wuming.android.tv.model.SiteViewModel;
import com.wuming.android.tv.ui.activity.CollectActivity;
import com.wuming.android.tv.ui.activity.VideoActivity;
import com.wuming.android.tv.ui.base.BaseFragment;
import com.wuming.android.tv.ui.custom.CustomRowPresenter;
import com.wuming.android.tv.ui.custom.CustomScroller;
import com.wuming.android.tv.ui.custom.CustomSelector;
import com.wuming.android.tv.ui.custom.GridFocusRestorer;
import com.wuming.android.tv.ui.presenter.FilterPresenter;
import com.wuming.android.tv.ui.presenter.VodPresenter;
import com.wuming.android.tv.utils.BundleUtil;
import com.wuming.android.tv.utils.Notify;
import com.wuming.android.tv.utils.ProviderDialog;
import com.wuming.android.tv.utils.ResUtil;
import com.google.common.collect.Lists;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class LeanbackTypeFragment extends BaseFragment implements CustomScroller.Callback, VodPresenter.OnClickListener, SwipeRefreshLayout.OnRefreshListener {

    private static final String TAG = "VodFolderTrace";
    private static final String LOGIN_ACTION = "LoginShow";
    private static final String LOGOUT_ACTION_PREFIX = "OpenDriveLogout:";
    private static final String STATE_FOCUS_KEY = "vodFocusKey";
    private static final String STATE_FOCUS_ROW = "vodFocusRow";

    private HashMap<String, String> mExtends;
    private FragmentTypeBinding mBinding;
    private ArrayObjectAdapter mAdapter;
    private ArrayObjectAdapter mLast;
    private CustomScroller mScroller;
    private SiteViewModel mViewModel;
    private List<Filter> mFilters;
    private boolean headerVisible;
    private boolean filterVisible;
    private boolean mDataInitialized;
    private boolean mOpenDriveLoginPending;
    private boolean mOpenDriveLogoutPending;
    private boolean mPendingNestedFocus;
    private String mPendingVodFocusKey;
    private int mPendingVodFocusRow;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            mPendingVodFocusKey = savedInstanceState.getString(STATE_FOCUS_KEY);
            mPendingVodFocusRow = savedInstanceState.getInt(STATE_FOCUS_ROW);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        String focusedKey = mBinding == null ? null : GridFocusRestorer.captureVodKey(mBinding.recycler);
        String key = focusedKey != null ? focusedKey : mPendingVodFocusKey;
        if (key != null) {
            outState.putString(STATE_FOCUS_KEY, key);
            outState.putInt(STATE_FOCUS_ROW, focusedKey != null ?
                    mBinding.recycler.getSelectedPosition() : mPendingVodFocusRow);
        }
        super.onSaveInstanceState(outState);
    }

    public static LeanbackTypeFragment newInstance(String key, String typeId, Style style, HashMap<String, String> extend, boolean folder, Result result) {
        Bundle args = new Bundle();
        args.putString("key", key);
        args.putString("typeId", typeId);
        args.putBoolean("folder", folder);
        args.putParcelable("style", style);
        args.putSerializable("extend", extend);
        if (result != null) args.putParcelable("result", result);
        LeanbackTypeFragment fragment = new LeanbackTypeFragment();
        fragment.setArguments(args);
        return fragment;
    }

    private String getKey() {
        return getArguments().getString("key");
    }

    private String getTypeId() {
        return getArguments().getString("typeId");
    }

    private boolean isFolder() {
        return getArguments().getBoolean("folder");
    }

    private boolean isNested() {
        return getArguments().getBoolean("nested");
    }

    private boolean isHome() {
        return "home".equals(getTypeId());
    }

    private Result getResult() {
        return BundleUtil.getParcelable(getArguments(), "result", Result.class);
    }

    private Style getStyle() {
        Style style = BundleUtil.getParcelable(getArguments(), "style", Style.class);
        return isFolder() ? Style.list() : getSite().getStyle(style);
    }

    private HashMap<String, String> getExtend() {
        return BundleUtil.getHashMap(getArguments(), "extend");
    }

    private List<Filter> getFilter() {
        return FilterCache.copy(getTypeId());
    }

    private Site getSite() {
        return VodConfig.get().getSite(getKey());
    }

    private LeanbackFolderFragment getParent() {
        return ((LeanbackFolderFragment) getParentFragment());
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentTypeBinding.inflate(inflater, container, false);
    }

    @Override
    protected void releaseBinding() {
        mBinding = null;
        mAdapter = null;
        mViewModel = null;
        mScroller = null;
    }

    @Override
    protected void initView() {
        mPendingNestedFocus = isNested();
        mBinding.swipeLayout.setColorSchemeResources(R.color.accent);
        mScroller = new CustomScroller(this);
        mExtends = getExtend();
        mFilters = getFilter();
        if (mDataInitialized && mAdapter != null) {
            mBinding.recycler.setAdapter(new ItemBridgeAdapter(mAdapter));
            mBinding.recycler.setHeader(getActivity(), R.id.recycler);
            mBinding.recycler.setVerticalSpacing(ResUtil.getDimensionPixelSize(R.dimen.vs_16));
        } else {
            setRecyclerView();
            setViewModel();
            setFilters();
            getVideo();
            mDataInitialized = true;
        }
    }

    @Override
    protected void initEvent() {
        mBinding.swipeLayout.setOnRefreshListener(this);
        mBinding.recycler.addOnScrollListener(mScroller);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mOpenDriveLoginPending && mBinding != null) {
            mOpenDriveLoginPending = false;
            onRefresh();
        }
    }

    @SuppressLint("RestrictedApi")
    private void setRecyclerView() {
        CustomSelector selector = new CustomSelector();
        selector.addPresenter(Vod.class, new VodPresenter(this, Style.list()));
        selector.addPresenter(ListRow.class,
                new CustomRowPresenter(R.dimen.vs_16), VodPresenter.class);
        selector.addPresenter(ListRow.class,
                new CustomRowPresenter(R.dimen.vs_8, FocusHighlight.ZOOM_FACTOR_NONE,
                        HorizontalGridView.FOCUS_SCROLL_ALIGNED), FilterPresenter.class);
        mBinding.recycler.setAdapter(new ItemBridgeAdapter(mAdapter = new ArrayObjectAdapter(selector)));
        mBinding.recycler.setHeader(getActivity(), R.id.recycler);
        mBinding.recycler.setVerticalSpacing(ResUtil.getDimensionPixelSize(R.dimen.vs_16));
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getResult().observe(getViewLifecycleOwner(), this::setAdapter);
        mViewModel.getAction().observe(getViewLifecycleOwner(), result -> {
            Notify.show(result.getMsg());
            if (mOpenDriveLogoutPending) {
                mOpenDriveLogoutPending = false;
                onRefresh();
            }
        });
    }

    private void setFilters() {
        for (Filter filter : mFilters) {
            if (mExtends.containsKey(filter.getKey())) {
                filter.setActivated(mExtends.get(filter.getKey()));
            }
        }
    }

    private void setClick(ArrayObjectAdapter adapter, String key, Value item) {
        for (int i = 0; i < adapter.size(); i++) ((Value) adapter.get(i)).setActivated(item);
        adapter.notifyArrayItemRangeChanged(0, adapter.size());
        if (item.isActivated()) mExtends.put(key, item.getV());
        else mExtends.remove(key);
        onRefresh();
    }

    private void getVideo() {
        mLast = null;
        checkFilter();
        mScroller.reset();
        if (isHome() && getResult() != null) {
            setAdapter(getResult());
            return;
        }
        getVideo(getTypeId(), "1");
    }

    private void getVideo(String typeId, String page) {
        mViewModel.categoryContent(getKey(), typeId, page, true, mExtends);
    }

    private void setAdapter(Result result) {
        boolean first = mScroller.first();
        boolean flag = mExtends.isEmpty();
        int size = result.getList().size();
        String currentKey = GridFocusRestorer.captureVodKey(mBinding.recycler);
        if (currentKey != null && mPendingVodFocusKey != null &&
                !currentKey.equals(mPendingVodFocusKey)) {
            mPendingVodFocusKey = null;
        }
        mBinding.progressLayout.showContent(first & flag, size);
        mBinding.swipeLayout.setRefreshing(false);
        mScroller.endLoading(result);
        if (size > 0) addVideo(result);
        if (size > 0 && mPendingVodFocusKey != null && !isHidden()) {
            String key = mPendingVodFocusKey;
            mPendingVodFocusKey = null;
            GridFocusRestorer.restoreVodAfterLayout(
                    mBinding.recycler, mAdapter, key, mPendingVodFocusRow, true);
        }
        if (size > 0 && mPendingNestedFocus) {
            mPendingNestedFocus = false;
            GridFocusRestorer.restoreIfFocusLost(mBinding.recycler, 0);
        }
    }

    void addVideo(Result result) {
        Style style = resolveDisplayStyle(result.getStyle(getStyle()), isNested());
        Log.d(TAG, "render site=" + getKey() + " type=" + getTypeId() + " nested=" + isNested() + " folder=" + isFolder() + " count=" + result.getList().size() + " firstItemStyle=" + describeStyle(result.getList().isEmpty() ? null : result.getList().get(0).getStyle()) + " resolvedStyle=" + describeStyle(style) + " columns=" + (style.isList() ? 1 : LeanbackProduct.getColumn(style)));
        if (style.isList()) mAdapter.addAll(mAdapter.size(), result.getList());
        else addGrid(result.getList(), style);
        checkMore();
    }

    static Style resolveDisplayStyle(Style style, boolean nested) {
        return nested && style.isList() ? Style.rect() : style;
    }

    private void checkMore() {
        if (isHome() || mScroller.isDisable() || mAdapter.size() >= 5) return;
        getVideo(getTypeId(), String.valueOf(mScroller.addPage()));
    }

    private boolean checkLastSize(List<Vod> items, Style style) {
        if (mLast == null || items.isEmpty()) return false;
        int size = LeanbackProduct.getColumn(style) - mLast.size();
        if (size == 0) return false;
        size = Math.min(size, items.size());
        mLast.addAll(mLast.size(), items.subList(0, size));
        addGrid(items.subList(size, items.size()), style);
        return true;
    }

    private void addGrid(List<Vod> items, Style style) {
        if (checkLastSize(items, style)) return;
        List<ListRow> rows = new ArrayList<>();
        VodPresenter presenter = new VodPresenter(this, style);
        for (List<Vod> part : Lists.partition(items, LeanbackProduct.getColumn(style))) {
            mLast = new ArrayObjectAdapter(presenter);
            mLast.addAll(0, part);
            rows.add(new ListRow(mLast));
        }
        mAdapter.addAll(mAdapter.size(), rows);
    }

    private ListRow getRow(Filter filter) {
        FilterPresenter presenter = new FilterPresenter(filter.getKey());
        ArrayObjectAdapter adapter = new ArrayObjectAdapter(presenter);
        presenter.setOnClickListener((key, item) -> setClick(adapter, key, item));
        adapter.setItems(filter.getValue(), null);
        return new ListRow(adapter);
    }

    private void showFilter() {
        List<ListRow> rows = new ArrayList<>();
        for (Filter filter : mFilters) rows.add(getRow(filter));
        postUi(() -> mBinding.recycler.scrollToPosition(0), 48);
        mAdapter.addAll(0, rows);
    }

    private void hideFilter() {
        mAdapter.removeItems(0, mFilters.size());
    }

    public void toggleFilter(boolean visible) {
        if (mFilters.isEmpty()) return;
        this.filterVisible = visible;
        if (visible) showFilter();
        else hideFilter();
    }

    private void checkFilter() {
        int adapterSize = mAdapter.size();
        int filterSize = filterVisible ? mFilters.size() : 0;
        if (adapterSize > filterSize) mAdapter.removeItems(filterSize, mAdapter.size() - filterSize);
        if (adapterSize == 0) mBinding.progressLayout.showProgress();
        else mBinding.swipeLayout.setRefreshing(true);
    }

    public void onRefresh() {
        if (mBinding != null) {
            String focusedKey = GridFocusRestorer.captureVodKey(mBinding.recycler);
            if (focusedKey != null) {
                mPendingVodFocusKey = focusedKey;
                mPendingVodFocusRow = mBinding.recycler.getSelectedPosition();
            }
        }
        if (isHome() && getResult() != null) {
            if (mAdapter != null) mAdapter.clear();
            getVideo();
            return;
        }
        getVideo();
    }

    @Override
    public void onItemClick(Vod item) {
        Log.d(TAG, "click site=" + getKey() + " type=" + getTypeId() + " name=" + item.getName() + " id=" + item.getId() + " folder=" + item.isFolder() + " itemStyle=" + describeStyle(item.getStyle()));
        if (item.isAction()) {
            if (OpenDriveLoginLauncher.open(requireActivity(), item.getAction())) {
                mOpenDriveLoginPending = true;
                return;
            }
            if (item.getAction().startsWith(LOGOUT_ACTION_PREFIX)) {
                confirmOpenDriveLogout(item);
                return;
            }
            // This provider owns the login dialog and snapshots its labels when
            // opened, so explain its required refresh step instead of implying
            // that the visible account status updates automatically.
            if (LOGIN_ACTION.equals(item.getAction())) {
                Notify.show(R.string.cloud_login_reopen_hint);
                ProviderDialog.relabelLoginDismissButton();
            }
            mViewModel.action(getKey(), item.getAction());
        } else if (item.isFolder()) {
            mPendingVodFocusKey = GridFocusRestorer.vodKey(item);
            mPendingVodFocusRow = mBinding.recycler.getSelectedPosition();
            getParent().openFolder(item.getId(), mExtends);
            headerVisible = mBinding.recycler.isHeaderVisible();
        } else {
            if (getSite().isIndex()) CollectActivity.start(requireActivity(), item.getName());
            else VideoActivity.start(requireActivity(), getKey(), item.getId(), item.getName(), item.getPic(), isFolder() ? item.getName() : null);
        }
    }

    private void confirmOpenDriveLogout(Vod item) {
        new com.wuming.android.tv.utils.TvAlertDialogBuilder(requireContext())
                .setTitle(item.getName())
                .setMessage(R.string.open_drive_logout_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.open_drive_logout, (dialog, which) -> {
                    mOpenDriveLogoutPending = true;
                    mViewModel.action(getKey(), item.getAction());
                })
                .show();
    }

    @Override
    public boolean onLongClick(Vod item) {
        if (item.isAction() || item.isFolder()) return false;
        CollectActivity.start(requireActivity(), item.getName());
        return true;
    }

    @Override
    public void onLoadMore(String page) {
        if (isHome()) return;
        mScroller.setLoading(true);
        getVideo(getTypeId(), page);
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (mBinding == null) return;
        if (hidden) {
            mBinding.recycler.showHeader();
        } else {
            if (headerVisible) mBinding.recycler.showHeader();
            else mBinding.recycler.hideHeader();
            String key = mPendingVodFocusKey;
            mPendingVodFocusKey = null;
            GridFocusRestorer.restoreVodAfterLayout(
                    mBinding.recycler, mAdapter, key,
                    key == null ? mBinding.recycler.getSelectedPosition() : mPendingVodFocusRow, false);
        }
    }

    static String describeStyle(Style style) {
        return style == null ? "null" : style.getType() + "(ratio=" + style.getRatio() + ",land=" + style.isLand() + ")";
    }
}
