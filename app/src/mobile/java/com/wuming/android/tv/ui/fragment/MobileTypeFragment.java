package com.wuming.android.tv.ui.fragment;

import android.content.res.Configuration;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.viewbinding.ViewBinding;

import com.wuming.android.tv.MobileProduct;
import com.wuming.android.tv.R;
import com.wuming.android.tv.api.config.VodConfig;
import com.wuming.android.tv.bean.Result;
import com.wuming.android.tv.bean.Site;
import com.wuming.android.tv.bean.Style;
import com.wuming.android.tv.bean.Value;
import com.wuming.android.tv.bean.Vod;
import com.wuming.android.tv.cloud.opendrive.OpenDriveLoginLauncher;
import com.wuming.android.tv.databinding.FragmentTypeBinding;
import com.wuming.android.tv.model.SiteViewModel;
import com.wuming.android.tv.recommendation.RecommendationRequest;
import com.wuming.android.tv.ui.activity.MobileFastSearchActivity;
import com.wuming.android.tv.ui.activity.VideoActivity;
import com.wuming.android.tv.ui.adapter.VodAdapter;
import com.wuming.android.tv.ui.base.BaseFragment;
import com.wuming.android.tv.ui.custom.CustomScroller;
import com.wuming.android.tv.utils.BundleUtil;
import com.wuming.android.tv.utils.Notify;
import com.wuming.android.tv.utils.ProviderDialog;
import com.wuming.android.tv.utils.ResUtil;

import java.util.HashMap;

public class MobileTypeFragment extends BaseFragment implements CustomScroller.Callback, VodAdapter.OnClickListener, SwipeRefreshLayout.OnRefreshListener {

    private static final String LOGIN_ACTION = "LoginShow";

    private final RecommendationRequest mRecommendations = new RecommendationRequest();
    private HashMap<String, String> mExtends;
    private FragmentTypeBinding mBinding;
    private CustomScroller mScroller;
    private SiteViewModel mViewModel;
    private VodAdapter mAdapter;
    private boolean mOpenDriveLoginPending;
    private long mPageStartedAt;

    public static MobileTypeFragment newInstance(String key, String typeId, Style style, HashMap<String, String> extend, boolean folder, int y) {
        Bundle args = new Bundle();
        args.putInt("y", y);
        args.putString("key", key);
        args.putString("typeId", typeId);
        args.putBoolean("folder", folder);
        args.putParcelable("style", style);
        args.putSerializable("extend", extend);
        MobileTypeFragment fragment = new MobileTypeFragment();
        fragment.setArguments(args);
        return fragment;
    }

    private String getKey() {
        return getArguments().getString("key");
    }

    private String getTypeId() {
        return getArguments().getString("typeId");
    }

    private Style getStyle() {
        return isFolder() ? Style.rect() : getSite().getStyle(BundleUtil.getParcelable(getArguments(), "style", Style.class));
    }

    private HashMap<String, String> getExtend() {
        return BundleUtil.getHashMap(getArguments(), "extend");
    }

    private int getY() {
        return getArguments().getInt("y");
    }

    private boolean isFolder() {
        return getArguments().getBoolean("folder");
    }

    private boolean isHome() {
        return "home".equals(getTypeId());
    }

    private Site getSite() {
        return VodConfig.get().getSite(getKey());
    }

    private MobileFolderFragment getParent() {
        return (MobileFolderFragment) getParentFragment();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentTypeBinding.inflate(inflater, container, false);
    }

    @Override
    protected void releaseBinding() {
        mRecommendations.invalidate();
        mBinding = null;
        mAdapter = null;
        mViewModel = null;
        mScroller = null;
    }

    @Override
    protected void initView() {
        mBinding.swipeLayout.setColorSchemeResources(R.color.accent);
        mBinding.progressLayout.showProgress();
        mScroller = new CustomScroller(this);
        mExtends = getExtend();
        setRecyclerView();
        setViewModel();
        getVideo();
    }

    @Override
    protected void initEvent() {
        mBinding.swipeLayout.setOnRefreshListener(this);
        mBinding.recycler.addOnScrollListener(mScroller);
    }

    @Override
    public void onResume() {
        super.onResume();
        com.bumptech.glide.Glide.with(this).resumeRequests();
        if (mOpenDriveLoginPending && mBinding != null) {
            mOpenDriveLoginPending = false;
            onRefresh();
        }
    }

    @Override
    public void onPause() {
        com.bumptech.glide.Glide.with(this).pauseRequests();
        super.onPause();
    }

    private void setRecyclerView() {
        mBinding.recycler.setTranslationY(-ResUtil.dp2px(getY()));
        mBinding.recycler.setHasFixedSize(true);
        setStyle(getStyle());
    }

    private void setStyle(Style style) {
        mBinding.recycler.setAdapter(mAdapter = new VodAdapter(this, style, MobileProduct.getSpec(requireActivity(), style)));
        mBinding.recycler.setLayoutManager(style.isList() ? new LinearLayoutManager(requireActivity()) : new GridLayoutManager(getContext(), MobileProduct.getColumn(requireActivity(), style)));
        updateGridGeometry(style);
    }

    private void updateGridGeometry(Style style) {
        if (style == null || style.isList() || mBinding == null || mAdapter == null) return;
        FragmentTypeBinding binding = mBinding;
        VodAdapter adapter = mAdapter;
        binding.recycler.post(() -> {
            if (mBinding != binding || mAdapter != adapter || !isAdded()) return;
            int availableWidth = binding.recycler.getWidth()
                    - binding.recycler.getPaddingStart()
                    - binding.recycler.getPaddingEnd();
            if (availableWidth <= 0) return;
            int spanCount = MobileProduct.getColumn(requireContext(), style);
            if (binding.recycler.getLayoutManager() instanceof GridLayoutManager manager) {
                manager.setSpanCount(spanCount);
            }
            adapter.setSize(MobileProduct.getSpec(requireContext(), style, availableWidth));
        });
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getResult().observe(getViewLifecycleOwner(), this::setAdapter);
        mViewModel.getAction().observe(getViewLifecycleOwner(), result -> Notify.show(result.getMsg()));
    }

    private void getHome() {
        mRecommendations.invalidate();
        mAdapter.clear(() -> {
            if (mViewModel != null) mViewModel.homeContent();
        });
    }

    private void getVideo() {
        mRecommendations.invalidate();
        mScroller.reset();
        mAdapter.clear(() -> {
            if (mBinding == null || mViewModel == null || mScroller == null) return;
            if (!mBinding.swipeLayout.isRefreshing()) mBinding.progressLayout.showProgress();
            if (isHome()) setAdapter(getParent().getResult());
            else getVideo(getTypeId(), "1");
        });
    }

    private void getVideo(String typeId, String page) {
        mPageStartedAt = android.os.SystemClock.elapsedRealtime();
        mViewModel.categoryContent(getKey(), typeId, page, true, mExtends);
    }

    private void setAdapter(Result result) {
        mRecommendations.invalidate();
        if (isHome() && mScroller != null && mScroller.first()) {
            mRecommendations.rank(getViewLifecycleOwner(), result.getList(), getKey(),
                    () -> VodConfig.get().getHome().getKey(), ranked -> {
                if (mBinding == null || mAdapter == null || mScroller == null) return;
                result.setList(ranked);
                setRankedAdapter(result);
            });
            return;
        }
        setRankedAdapter(result);
    }

    private void setRankedAdapter(Result result) {
        if (mBinding == null || mAdapter == null || mScroller == null) return;
        boolean first = mScroller.first();
        int size = result.getList().size();
        if (mPageStartedAt > 0) {
            com.wuming.android.tv.utils.AppLog.d("CatalogPage", "content ready items=" + size
                    + " elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - mPageStartedAt));
            mPageStartedAt = 0;
        }
        mBinding.progressLayout.showContent(first, size);
        mBinding.swipeLayout.setRefreshing(false);
        mScroller.endLoading(result);
        if (size > 0) addVideo(result);
    }

    private void addVideo(Result result) {
        Style style = result.getVod().getStyle(getStyle());
        if (!style.equals(mAdapter.getStyle())) setStyle(style);
        mAdapter.addAll(result.getList(), this::checkMore);
    }

    private void checkMore() {
        FragmentTypeBinding binding = mBinding;
        CustomScroller scroller = mScroller;
        if (binding == null || scroller == null) return;
        binding.recycler.post(() -> {
            if (mBinding != binding || mScroller != scroller || mViewModel == null) return;
            if (scroller.isDisable() || binding.recycler.canScrollVertically(1) || binding.recycler.getScrollState() != 0 || isHome()) return;
            getVideo(getTypeId(), String.valueOf(scroller.addPage()));
        });
    }

    public void scrollToTop() {
        mBinding.recycler.smoothScrollToPosition(0);
    }

    public void setFilter(String key, Value value) {
        if (value.isActivated()) mExtends.put(key, value.getV());
        else mExtends.remove(key);
        onRefresh();
    }

    @Override
    public void onRefresh() {
        if (isHome()) getHome();
        else getVideo();
    }

    @Override
    public void onLoadMore(String page) {
        if (isHome()) return;
        mScroller.setLoading(true);
        getVideo(getTypeId(), page);
    }

    @Override
    public void onItemClick(Vod item) {
        if (item.isAction()) {
            if (OpenDriveLoginLauncher.open(requireActivity(), item.getAction())) {
                mOpenDriveLoginPending = true;
                return;
            }
            if (LOGIN_ACTION.equals(item.getAction())) {
                Notify.show(R.string.cloud_login_reopen_hint);
                ProviderDialog.stopLoginQrHandoff();
                ProviderDialog.relabelLoginDismissButton();
            }
            mViewModel.action(getKey(), item.getAction());
        } else if (item.isFolder()) {
            getParent().openFolder(item.getId(), mExtends);
        } else {
            if (getSite().isIndex()) MobileFastSearchActivity.start(requireActivity(), item.getName());
            else VideoActivity.start(requireActivity(), getKey(), item.getId(), item.getName(), item.getPic(), isFolder() ? item.getName() : null);
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mBinding == null || mAdapter == null) return;
        Style style = getStyle();
        updateGridGeometry(style);
    }

    @Override
    public boolean onLongClick(Vod item) {
        if (item.isAction() || item.isFolder()) return false;
        MobileFastSearchActivity.start(requireActivity(), item.getName());
        return true;
    }
}
