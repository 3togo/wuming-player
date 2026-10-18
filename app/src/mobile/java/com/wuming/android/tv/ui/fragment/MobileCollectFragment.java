package com.wuming.android.tv.ui.fragment;
import com.wuming.android.tv.setting.DisplaySettings;

import android.content.res.Configuration;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.MenuProvider;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.wuming.android.tv.R;
import com.wuming.android.tv.MobileProduct;
import com.wuming.android.tv.bean.Collect;
import com.wuming.android.tv.bean.Result;
import com.wuming.android.tv.bean.SearchFilter;
import com.wuming.android.tv.bean.Site;
import com.wuming.android.tv.bean.Vod;
import com.wuming.android.tv.databinding.FragmentCollectBinding;
import com.wuming.android.tv.model.SiteViewModel;
import com.wuming.android.tv.search.CollectionWorkflow;
import com.wuming.android.tv.ui.activity.FolderActivity;
import com.wuming.android.tv.ui.activity.VideoActivity;
import com.wuming.android.tv.ui.adapter.MobileCollectAdapter;
import com.wuming.android.tv.ui.adapter.SearchAdapter;
import com.wuming.android.tv.ui.base.BaseFragment;
import com.wuming.android.tv.ui.custom.CustomScroller;
import com.wuming.android.tv.utils.LayoutConfig;

import java.util.List;

public class MobileCollectFragment extends BaseFragment implements MenuProvider, MobileCollectAdapter.OnClickListener, SearchAdapter.OnClickListener, CustomScroller.Callback {

    private FragmentCollectBinding mBinding;
    private MobileCollectAdapter mCollectAdapter;
    private SearchAdapter mSearchAdapter;
    private CustomScroller mScroller;
    private SiteViewModel mViewModel;
    private List<Site> mSites;

    public static MobileCollectFragment newInstance(String keyword) {
        Bundle args = new Bundle();
        args.putString("keyword", keyword);
        MobileCollectFragment fragment = new MobileCollectFragment();
        fragment.setArguments(args);
        return fragment;
    }

    private String getKeyword() {
        return getArguments().getString("keyword");
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentCollectBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void releaseBinding() {
        if (mBinding != null) {
            mBinding.collect.setAdapter(null);
            mBinding.recycler.setAdapter(null);
        }
        mBinding = null;
        mCollectAdapter = null;
        mViewModel = null;
        mScroller = null;
        mSearchAdapter = null;
    }

    @Override
    protected void initMenu() {
        if (isHidden()) return;
        AppCompatActivity activity = (AppCompatActivity) requireActivity();
        activity.setSupportActionBar(mBinding.toolbar);
        activity.getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        activity.addMenuProvider(this, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
        activity.setTitle(getKeyword());
    }

    @Override
    protected void initView() {
        mScroller = new CustomScroller(this);
        setRecyclerView();
        setViewModel();
        setSites();
        // Post width setting to run after view is laid out
        mBinding.llCollect.post(this::setWidth);
        search();
    }

    @Override
    protected void initEvent() {
        mBinding.toolbar.setOnClickListener(v -> {
            Bundle result = new Bundle();
            result.putBoolean("edit", true);
            getParentFragmentManager().setFragmentResult("result", result);
            getParentFragmentManager().popBackStack();
        });
    }

    private void setRecyclerView() {
        mBinding.collect.setItemAnimator(null);
        mBinding.collect.setHasFixedSize(true);
        mBinding.collect.setAdapter(mCollectAdapter = new MobileCollectAdapter(this));
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.addOnScrollListener(mScroller);
        mBinding.recycler.setAdapter(mSearchAdapter = new SearchAdapter(requireContext(), this));
        mSearchAdapter.setKeyword(getKeyword());
        ((GridLayoutManager) (mBinding.recycler.getLayoutManager())).setSpanCount(LayoutConfig.getSearchGridSpanCount(requireContext()));
        mBinding.recycler.post(this::updateGridGeometry);
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class).init();
        mViewModel.getSearch().observe(getViewLifecycleOwner(), this::setCollect);
        mViewModel.getResult().observe(getViewLifecycleOwner(), this::setSearch);
        mViewModel.getSearchProgress().observe(getViewLifecycleOwner(), progress -> {
            if (progress == null) return;
            if (progress.isComplete()) {
                mBinding.toolbar.setSubtitle(null);
            } else {
                mBinding.toolbar.setSubtitle(progress.getProgressText());
            }
        });
    }

    private void setSites() {
        mSites = CollectionWorkflow.rankedSearchableSites();
    }

    private void setWidth() {
        if (mBinding == null) return;
        int targetWidth = LayoutConfig.getSiteColumnWidthPixels(requireContext());
        int railWidth = LayoutConfig.getSiteRailWidthPixels(requireContext(), mBinding.llCollect);

        mBinding.llCollect.getLayoutParams().width = targetWidth;
        mBinding.llCollect.setMinimumWidth(targetWidth);
        mBinding.collect.getLayoutParams().width = railWidth;
        mBinding.collect.setMinimumWidth(railWidth);

        mBinding.llCollect.requestLayout();
        mBinding.recycler.post(this::updateGridGeometry);
    }

    private void updateGridGeometry() {
        if (mBinding == null || mSearchAdapter == null) return;
        if (!(mBinding.recycler.getLayoutManager() instanceof GridLayoutManager manager)) return;
        int viewportWidth = mBinding.recycler.getWidth()
                - mBinding.recycler.getPaddingStart()
                - mBinding.recycler.getPaddingEnd();
        if (viewportWidth <= 0) return;
        mSearchAdapter.setSize(MobileProduct.getSpecForViewport(requireContext(), viewportWidth, manager.getSpanCount()));
    }

    private void search() {
        if (mSites.isEmpty()) return;
        mCollectAdapter.setItems(List.of(Collect.all()), () -> {
            if (mViewModel != null) mViewModel.searchContent(mSites, getKeyword(), false);
        });
    }

    private void toggleStrictSearch() {
        DisplaySettings.putStrictSearch(!DisplaySettings.isStrictSearch());
        requireActivity().invalidateOptionsMenu();
        mViewModel.stopSearch();
        mCollectAdapter.clear();
        mSearchAdapter.setItems(List.of());
        search();
    }


    private void setCollect(Result result) {
        if (result == null || result.getList().isEmpty()) return;
        if (mCollectAdapter.getPosition() == 0) mSearchAdapter.addAll(result.getList());
        mCollectAdapter.add(Collect.create(result.getList()));
        mCollectAdapter.add(result.getList());
    }

    private void setSearch(Result result) {
        if (result == null) return;
        mScroller.endLoading(result);
        boolean same = !result.getList().isEmpty() && mCollectAdapter.getActivated().getSite().equals(result.getVod().getSite());
        if (same) mCollectAdapter.getActivated().getList().addAll(result.getList());
        if (same) mSearchAdapter.addAll(result.getList());
    }

    @Override
    public void onItemClick(int position, Collect item) {
        FragmentCollectBinding binding = mBinding;
        mSearchAdapter.setItems(item.getList(), () -> {
            if (mBinding == binding && binding != null) binding.recycler.scrollToPosition(0);
        });
        mCollectAdapter.setActivated(position);
        mScroller.setPage(item.getPage());
    }

    @Override
    public void onItemClick(Vod item) {
        if (item.isFolder()) FolderActivity.start(requireActivity(), item.getSiteKey(), Result.folder(item));
        else VideoActivity.collect(requireActivity(), item.getSiteKey(), item.getId(), item.getName(), item.getPic());
    }

    @Override
    public void onLoadMore(String page) {
        Collect activated = mCollectAdapter.getActivated();
        if ("all".equals(activated.getSite().getKey())) return;
        String query = CollectionWorkflow.pageQuery(activated, getKeyword());
        mViewModel.searchContent(activated.getSite(), query, false, page);
        activated.setPage(Integer.parseInt(page));
        mScroller.setLoading(true);
    }

    @Override
    public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
        menuInflater.inflate(R.menu.menu_collect, menu);
    }

    @Override
    public void onPrepareMenu(@NonNull Menu menu) {
        MenuItem strict = menu.findItem(R.id.action_strict_search);
        boolean enabled = DisplaySettings.isStrictSearch();
        strict.setChecked(enabled);
        strict.setIcon(enabled ? R.drawable.ic_search_strict_on : R.drawable.ic_search_strict_off);
        strict.setTitle(enabled ? R.string.search_strict_on : R.string.search_strict_off);
    }

    @Override
    public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
        if (menuItem.getItemId() == android.R.id.home) {
            requireActivity().getOnBackPressedDispatcher().onBackPressed();
            return true;
        }
        if (menuItem.getItemId() == R.id.action_strict_search) {
            toggleStrictSearch();
            return true;
        }
        SearchFilter filter = mSearchAdapter.getFilter();
        if (menuItem.getItemId() == R.id.sort_relevance) {
            filter.setSort(SearchFilter.Sort.RELEVANCE);
            menuItem.setChecked(true);
        } else if (menuItem.getItemId() == R.id.sort_name) {
            filter.setSort(SearchFilter.Sort.NAME);
            menuItem.setChecked(true);
        } else if (menuItem.getItemId() == R.id.sort_year) {
            filter.setSort(SearchFilter.Sort.YEAR);
            menuItem.setChecked(true);
        } else if (menuItem.getItemId() == R.id.filter_all) {
            filter.setType(SearchFilter.Type.ALL);
            menuItem.setChecked(true);
        } else if (menuItem.getItemId() == R.id.filter_movie) {
            filter.setType(SearchFilter.Type.MOVIE);
            menuItem.setChecked(true);
        } else if (menuItem.getItemId() == R.id.filter_tv) {
            filter.setType(SearchFilter.Type.TV);
            menuItem.setChecked(true);
        } else {
            return false;
        }
        mSearchAdapter.setFilter(filter);
        return true;
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mBinding == null) return;
        int spanCount = LayoutConfig.getSearchGridSpanCount(requireContext());
        ((GridLayoutManager) mBinding.recycler.getLayoutManager()).setSpanCount(spanCount);
        setWidth();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        if (hidden) requireActivity().removeMenuProvider(this);
        else initMenu();
    }

    @Override
    public void onDestroyView() {
        mViewModel.stopSearch();
        requireActivity().removeMenuProvider(this);
        super.onDestroyView();
    }
}
