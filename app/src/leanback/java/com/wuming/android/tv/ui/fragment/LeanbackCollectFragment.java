package com.wuming.android.tv.ui.fragment;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.ListRow;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewbinding.ViewBinding;

import com.wuming.android.tv.LeanbackProduct;
import com.wuming.android.tv.R;
import com.wuming.android.tv.bean.Collect;
import com.wuming.android.tv.bean.SearchFilter;
import com.wuming.android.tv.bean.Result;
import com.wuming.android.tv.bean.Style;
import com.wuming.android.tv.bean.Vod;
import com.wuming.android.tv.databinding.FragmentTypeBinding;
import com.wuming.android.tv.model.SiteViewModel;
import com.wuming.android.tv.search.CollectionWorkflow;
import com.wuming.android.tv.ui.activity.VideoActivity;
import com.wuming.android.tv.ui.activity.VodActivity;
import com.wuming.android.tv.ui.base.BaseFragment;
import com.wuming.android.tv.ui.custom.CustomRowPresenter;
import com.wuming.android.tv.ui.custom.CustomScroller;
import com.wuming.android.tv.ui.custom.CustomSelector;
import com.wuming.android.tv.ui.presenter.VodPresenter;
import com.wuming.android.tv.utils.ResUtil;
import com.wuming.android.tv.utils.CollectPageMerger;
import com.google.common.collect.Lists;

import java.util.ArrayList;
import java.util.List;

public class LeanbackCollectFragment extends BaseFragment implements CustomScroller.Callback, VodPresenter.OnClickListener {

    private FragmentTypeBinding mBinding;
    private ArrayObjectAdapter mAdapter;
    private ArrayObjectAdapter mLast;
    private CustomScroller mScroller;
    private SiteViewModel mViewModel;
    private Collect mCollect;
    private String mKeyword;
    private List<Vod> allVods = new ArrayList<>();
    private boolean mDataInitialized;

    public static LeanbackCollectFragment newInstance(String keyword, Collect collect) {
        Bundle args = new Bundle();
        args.putString("keyword", keyword);
        LeanbackCollectFragment fragment = new LeanbackCollectFragment().setCollect(collect);
        fragment.setArguments(args);
        return fragment;
    }

    private String getKeyword() {
        return mKeyword = mKeyword == null ? getArguments().getString("keyword") : mKeyword;
    }

    private LeanbackCollectFragment setCollect(Collect collect) {
        this.mCollect = collect;
        return this;
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
        if (mDataInitialized && mAdapter != null) {
            mScroller = new CustomScroller(this);
            mBinding.recycler.setAdapter(new ItemBridgeAdapter(mAdapter));
            mBinding.recycler.addOnScrollListener(mScroller);
            mBinding.recycler.setHeader(getActivity(), R.id.result, R.id.recycler);
            mBinding.recycler.setVerticalSpacing(ResUtil.getDimensionPixelSize(R.dimen.vs_16));
        } else {
            setRecyclerView();
            setViewModel();
            addVideo(mCollect);
            mDataInitialized = true;
        }
    }

    private void setRecyclerView() {
        CustomSelector selector = new CustomSelector();
        selector.addPresenter(ListRow.class,
                new CustomRowPresenter(R.dimen.vs_16), VodPresenter.class);
        mBinding.recycler.setAdapter(new ItemBridgeAdapter(mAdapter = new ArrayObjectAdapter(selector)));
        mBinding.recycler.addOnScrollListener(mScroller = new CustomScroller(this));
        mBinding.recycler.setHeader(getActivity(), R.id.result, R.id.recycler);
        mBinding.recycler.setVerticalSpacing(ResUtil.getDimensionPixelSize(R.dimen.vs_16));
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getResult().observe(getViewLifecycleOwner(), result -> {
            mScroller.endLoading(result);
            addVideo(result.getList());
        });
    }

    private void addVideo(Collect collect) {
        if (collect != null) addVideo(collect.getList());
    }

    public void addVideo(List<Vod> items) {
        if (isSitePage() && !allVods.isEmpty()) {
            appendRows(CollectPageMerger.appendUnique(allVods, items));
            return;
        }
        List<Vod> merged = CollectionWorkflow.mergeAndRank(allVods, items);
        allVods.clear();
        allVods.addAll(merged);
        
        if (mAdapter == null || getActivity() == null || getActivity().isFinishing()) return;
        
        // Clear and rebuild adapter with sorted videos
        mAdapter.clear();
        mLast = null;
        List<ListRow> rows = new ArrayList<>();
        VodPresenter presenter = new VodPresenter(this, Style.rect(), true).keyword(getKeyword());
        for (List<Vod> part : Lists.partition(allVods, LeanbackProduct.SEARCH_COLUMN)) {
            mLast = new ArrayObjectAdapter(presenter);
            mLast.addAll(0, part);
            rows.add(new ListRow(mLast));
        }
        mAdapter.addAll(0, rows);
    }

    private boolean isSitePage() {
        return mCollect != null && !"all".equals(mCollect.getSite().getKey());
    }

    /** Append pagination results without clearing the adapter and losing Leanback focus. */
    private void appendRows(List<Vod> items) {
        if (items.isEmpty() || mAdapter == null) return;
        int offset = 0;
        if (mLast != null && mLast.size() < LeanbackProduct.SEARCH_COLUMN) {
            int count = Math.min(LeanbackProduct.SEARCH_COLUMN - mLast.size(), items.size());
            mLast.addAll(mLast.size(), items.subList(0, count));
            offset = count;
        }
        if (offset >= items.size()) return;
        List<ListRow> rows = new ArrayList<>();
        VodPresenter presenter = new VodPresenter(this, Style.rect(), true).keyword(getKeyword());
        for (List<Vod> part : Lists.partition(items.subList(offset, items.size()), LeanbackProduct.SEARCH_COLUMN)) {
            mLast = new ArrayObjectAdapter(presenter);
            mLast.addAll(0, part);
            rows.add(new ListRow(mLast));
        }
        mAdapter.addAll(mAdapter.size(), rows);
    }

    public void sortResults(SearchFilter filter, String keyword) {
        List<Vod> filtered = CollectionWorkflow.filterAndSort(allVods, filter, keyword);
        mAdapter.clear();
        mLast = null;
        List<ListRow> rows = new ArrayList<>();
        VodPresenter presenter = new VodPresenter(this, Style.rect(), true).keyword(keyword);
        for (List<Vod> part : Lists.partition(filtered, LeanbackProduct.SEARCH_COLUMN)) {
            mLast = new ArrayObjectAdapter(presenter);
            mLast.addAll(0, part);
            rows.add(new ListRow(mLast));
        }
        mAdapter.addAll(0, rows);
    }

    @Override
    public void onItemClick(Vod item) {
        requireActivity().setResult(Activity.RESULT_OK);
        if (item.isFolder()) VodActivity.start(requireActivity(), item.getSiteKey(), Result.folder(item));
        else VideoActivity.collect(requireActivity(), item.getSiteKey(), item.getId(), item.getName(), item.getPic());
    }

    @Override
    public boolean onLongClick(Vod item) {
        return false;
    }

    @Override
    public void onLoadMore(String page) {
        if (mCollect == null || "all".equals(mCollect.getSite().getKey())) return;
        String query = CollectionWorkflow.pageQuery(mCollect, getKeyword());
        mViewModel.searchContent(mCollect.getSite(), query, false, page);
        mScroller.setLoading(true);
    }

    @Override
    public void onPause() {
        super.onPause();
    }
}
