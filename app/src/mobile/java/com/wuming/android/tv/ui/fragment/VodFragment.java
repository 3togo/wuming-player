package com.wuming.android.tv.ui.fragment;

import com.wuming.android.tv.utils.TextScale;

import android.app.Activity;
import android.content.Intent;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewbinding.ViewBinding;
import androidx.viewpager.widget.ViewPager;

import com.wuming.android.tv.R;
import com.wuming.android.tv.api.config.VodConfig;
import com.wuming.android.tv.bean.Class;
import com.wuming.android.tv.bean.Config;
import com.wuming.android.tv.bean.Result;
import com.wuming.android.tv.bean.Site;
import com.wuming.android.tv.bean.Value;
import com.wuming.android.tv.databinding.FragmentVodBinding;
import com.wuming.android.tv.event.CastEvent;
import com.wuming.android.tv.event.ConfigEvent;
import com.wuming.android.tv.event.RefreshEvent;
import com.wuming.android.tv.event.StateEvent;
import com.wuming.android.tv.impl.Callback;
import com.wuming.android.tv.impl.ConfigCallback;
import com.wuming.android.tv.impl.FilterCallback;
import com.wuming.android.tv.impl.SiteCallback;
import com.wuming.android.tv.model.SiteViewModel;
import com.wuming.android.tv.ui.activity.MobileFastSearchActivity;
import com.wuming.android.tv.ui.activity.HistoryActivity;
import com.wuming.android.tv.ui.activity.MobileKeepActivity;
import com.wuming.android.tv.ui.activity.VideoActivity;
import com.wuming.android.tv.ui.adapter.MobileTypeAdapter;
import com.wuming.android.tv.ui.base.BaseFragment;
import com.wuming.android.tv.ui.dialog.FilterDialog;
import com.wuming.android.tv.ui.dialog.HistoryDialog;
import com.wuming.android.tv.ui.dialog.LinkDialog;
import com.wuming.android.tv.ui.dialog.ReceiveDialog;
import com.wuming.android.tv.ui.dialog.MobileSiteDialog;
import com.wuming.android.tv.utils.FileChooser;
import com.wuming.android.tv.utils.ImgUtil;
import com.wuming.android.tv.utils.LayoutConfig;
import com.wuming.android.tv.utils.Notify;
import com.wuming.android.tv.utils.ResUtil;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class VodFragment extends BaseFragment implements ConfigCallback, SiteCallback, FilterCallback, MobileTypeAdapter.OnClickListener {

    private FragmentVodBinding mBinding;
    private SiteViewModel mViewModel;
    private MobileTypeAdapter mAdapter;
    private Result mResult;
    private String prefetchedHomeKey;

    public static VodFragment newInstance() {
        return new VodFragment();
    }

    private MobileFolderFragment getFragment() {
        return (MobileFolderFragment) mBinding.pager.getAdapter().instantiateItem(mBinding.pager, mBinding.pager.getCurrentItem());
    }

    private Site getHome() {
        return VodConfig.get().getHome();
    }

    private Config getConfig() {
        return VodConfig.get().getConfig();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentVodBinding.inflate(inflater, container, false);
    }

    @Override
    protected void releaseBinding() {
        mBinding = null;
        mAdapter = null;
        mViewModel = null;
    }

    @Override
    protected void initView() {
        EventBus.getDefault().register(this);
        applyResponsiveLayout();
        setRecyclerView();
        showProgress();
        setViewModel();
        setTitle();
        setLogo();
        if (!VodConfig.get().getSites().isEmpty() && !getHome().getKey().isEmpty()) {
            prefetchedHomeKey = getHome().getKey();
            mViewModel.homeContent();
        }
    }

    private void compactLandscapeHeader() {
        if (!ResUtil.isLand(requireContext())) return;
        mBinding.title.setSelected(true);
        mBinding.title.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        int toolbarHeight = LayoutConfig.getMobileHomeToolbarHeightPixels(requireContext());
        int categoryHeight = LayoutConfig.getMobileHomeCategoryRowHeightPixels(requireContext());
        int logoSize = LayoutConfig.getMobileHomeLogoSizePixels(toolbarHeight);

        ViewGroup.LayoutParams toolbarParams = mBinding.toolbar.getLayoutParams();
        toolbarParams.height = toolbarHeight;
        mBinding.toolbar.setLayoutParams(toolbarParams);
        int contentInset = LayoutConfig.getMobileHomeToolbarContentInsetPixels(toolbarHeight);
        mBinding.toolbar.setContentInsetsRelative(contentInset, contentInset);

        ViewGroup.LayoutParams logoParams = mBinding.logo.getLayoutParams();
        logoParams.width = logoSize;
        logoParams.height = logoSize;
        if (logoParams instanceof ViewGroup.MarginLayoutParams margins) {
            margins.setMarginEnd(LayoutConfig.getMobileToolbarActionMarginPixels(toolbarHeight));
        }
        mBinding.logo.setLayoutParams(logoParams);
        TextScale.bind(mBinding.title);

        ViewGroup.LayoutParams categoryParams = mBinding.type.getLayoutParams();
        categoryParams.height = categoryHeight;
        mBinding.type.setLayoutParams(categoryParams);
        int chipHeight = LayoutConfig.getMobileHomeCategoryChipHeightPixels(requireContext());
        int verticalPadding = Math.max(0,
                (categoryHeight - chipHeight) / 2);
        int horizontalPadding = Math.max(0, contentInset
                - LayoutConfig.getMobileHomeCategoryInsetAdjustmentPixels(chipHeight));
        mBinding.type.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);

        int actionSize = toolbarHeight;
        for (int id : new int[]{R.id.search, R.id.keep, R.id.history}) {
            View action = mBinding.toolbar.findViewById(id);
            if (action == null) continue;
            ViewGroup.LayoutParams actionParams = action.getLayoutParams();
            actionParams.width = actionSize;
            actionParams.height = actionSize;
            action.setLayoutParams(actionParams);
            action.setScaleX(0.62f);
            action.setScaleY(0.62f);
        }

        styleFab(mBinding.link,
                LayoutConfig.getMobileHomeFabSizePixels(requireContext()),
                LayoutConfig.getMobileHomeFabIconSizePixels(requireContext()),
                LayoutConfig.getMobileHomeFabMarginPixels(requireContext()));
        styleFab(mBinding.filter,
                LayoutConfig.getMobileHomeFabSizePixels(requireContext()),
                LayoutConfig.getMobileHomeFabIconSizePixels(requireContext()),
                LayoutConfig.getMobileHomeFabMarginPixels(requireContext()));
        styleFab(mBinding.top,
                LayoutConfig.getMobileHomeFabSizePixels(requireContext()),
                LayoutConfig.getMobileHomeFabIconSizePixels(requireContext()),
                LayoutConfig.getMobileHomeFabMarginPixels(requireContext()));
    }

    public void applyResponsiveLayout() {
        if (mBinding == null) return;
        if (ResUtil.isLand(requireContext())) compactLandscapeHeader();
        else stylePortraitLayout();
    }

    private void stylePortraitLayout() {
        mBinding.title.setSelected(false);
        mBinding.title.setEllipsize(TextUtils.TruncateAt.END);
        mBinding.title.setMaxWidth(
                LayoutConfig.getMobilePortraitTitleMaxWidthPixels(requireContext()));
        TextScale.bind(mBinding.title);
        int size = LayoutConfig.getMobilePortraitFabSizePixels(requireContext());
        int icon = LayoutConfig.getMobilePortraitFabIconSizePixels(requireContext());
        int margin = LayoutConfig.getMobilePortraitFabMarginPixels(requireContext());
        styleFab(mBinding.link, size, icon, margin);
        styleFab(mBinding.filter, size, icon, margin);
        styleFab(mBinding.top, size, icon, margin);
    }

    private void styleFab(FloatingActionButton fab, int size, int iconSize, int margin) {
        fab.setEnsureMinTouchTargetSize(false);
        fab.setCustomSize(size);
        fab.setMaxImageSize(iconSize);
        if (fab.getLayoutParams() instanceof ViewGroup.MarginLayoutParams params) {
            params.width = size;
            params.height = size;
            params.setMargins(margin, margin, margin, margin);
            fab.setLayoutParams(params);
        }
    }

    @Override
    protected void initEvent() {
        mBinding.top.setOnClickListener(this::onTop);
        mBinding.logo.setOnClickListener(this::onLogo);
        mBinding.link.setOnClickListener(this::onLink);
        mBinding.title.setOnClickListener(this::onSite);
        mBinding.filter.setOnClickListener(this::onFilter);
        mBinding.filter.setOnLongClickListener(this::onLink);
        mBinding.toolbar.setOnMenuItemClickListener(this::onMenuItemClick);
        mBinding.appBar.addOnOffsetChangedListener((appBarLayout, verticalOffset) -> {
            float factor = Math.abs(verticalOffset * 1f / appBarLayout.getTotalScrollRange());
            int padding = (int) (ResUtil.getDimensionPixelSize(R.dimen.vs_12) * factor);
            if (mBinding.type.getPaddingTop() == padding) return;
            mBinding.type.setPadding(mBinding.type.getPaddingStart(), padding, mBinding.type.getPaddingEnd(), mBinding.type.getPaddingBottom());
        });
        mBinding.pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                mBinding.type.smoothScrollToPosition(position);
                mAdapter.setActivated(position);
                setFabVisible(position);
            }
        });
    }

    private void setRecyclerView() {
        mBinding.type.setHasFixedSize(true);
        mBinding.type.setItemAnimator(null);
        mBinding.type.setAdapter(mAdapter = new MobileTypeAdapter(this));
        mBinding.pager.setAdapter(new PageAdapter(getChildFragmentManager()));
        mBinding.pager.setOffscreenPageLimit(1);
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getResult().observe(getViewLifecycleOwner(), this::setAdapter);
    }

    private void setAdapter(Result result) {
        mAdapter.addAll(mResult = result);
        mBinding.pager.getAdapter().notifyDataSetChanged();
        setFabVisible(0);
        hideProgress();
        showContent();
    }

    private void setFabVisible(int position) {
        if (mAdapter.getItemCount() == 0) {
            mBinding.top.setVisibility(View.INVISIBLE);
            mBinding.link.setVisibility(View.VISIBLE);
            mBinding.filter.setVisibility(View.GONE);
        } else if (!mAdapter.get(position).getFilters().isEmpty()) {
            mBinding.top.setVisibility(View.INVISIBLE);
            mBinding.link.setVisibility(View.GONE);
            mBinding.filter.show();
        } else if (position == 0 || mAdapter.get(position).getFilters().isEmpty()) {
            mBinding.top.setVisibility(View.INVISIBLE);
            mBinding.filter.setVisibility(View.GONE);
            mBinding.link.show();
        }
    }

    private void setTitle() {
        List<String> items = Arrays.asList(getHome().getName(), getConfig().getName(), getString(R.string.app_name));
        Optional<String> optional = items.stream().filter(s -> !TextUtils.isEmpty(s)).findFirst();
        optional.ifPresent(s -> mBinding.title.setText(s));
    }

    private void onTop(View view) {
        getFragment().scrollToTop();
        mBinding.top.setVisibility(View.INVISIBLE);
        if (mBinding.filter.getVisibility() == View.INVISIBLE) mBinding.filter.show();
        else if (mBinding.link.getVisibility() == View.INVISIBLE) mBinding.link.show();
    }

    private boolean onLink(View view) {
        LinkDialog.create(this).launcher(launcher).show();
        return true;
    }

    private void onLogo(View view) {
        HistoryDialog.create(this).readOnly().type(0).show();
    }

    private void onSite(View view) {
        MobileSiteDialog.create(this).change().show();
    }

    private void onFilter(View view) {
        if (mAdapter.getItemCount() > 0) FilterDialog.create().filter(mAdapter.get(mBinding.pager.getCurrentItem()).getFilters()).show(this);
    }

    private boolean onMenuItemClick(MenuItem item) {
        if (item.getItemId() == R.id.keep) MobileKeepActivity.start(requireActivity());
        else if (item.getItemId() == R.id.search) MobileFastSearchActivity.start(requireActivity());
        else if (item.getItemId() == R.id.history) HistoryActivity.start(requireActivity());
        return true;
    }

    private void showProgress() {
        mBinding.progress.getRoot().setVisibility(View.VISIBLE);
    }

    private void hideProgress() {
        mBinding.progress.getRoot().setVisibility(View.GONE);
    }

    private void hideContent() {
        mBinding.type.setVisibility(View.INVISIBLE);
        mBinding.pager.setVisibility(View.INVISIBLE);
    }

    private void showContent() {
        mBinding.type.setVisibility(View.VISIBLE);
        mBinding.pager.setVisibility(View.VISIBLE);
    }

    private void homeContent() {
        showProgress();
        setFabVisible(0);
        mAdapter.clear();
        mViewModel.homeContent();
        mBinding.pager.setAdapter(new PageAdapter(getChildFragmentManager()));
    }

    public Result getResult() {
        return mResult == null ? new Result() : mResult;
    }

    private void setLogo() {
        ImgUtil.logo(mBinding.logo);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        if (event.type() == ConfigEvent.Type.VOD) setLogo();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        switch (event.getType()) {
            case HOME:
                setTitle();
                if (prefetchedHomeKey != null && prefetchedHomeKey.equals(getHome().getKey())
                        && mResult != null && mResult.getList() != null && !mResult.getList().isEmpty()) {
                    prefetchedHomeKey = null;
                    break;
                }
                prefetchedHomeKey = null;
            case SIZE:
                homeContent();
                break;
            case CATEGORY:
                getFragment().onRefresh();
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onStateEvent(StateEvent event) {
        switch (event.type()) {
            case EMPTY:
                hideProgress();
                break;
            case PROGRESS:
                showProgress();
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onCastEvent(CastEvent event) {
        ReceiveDialog.create().event(event).show(this);
    }

    @Override
    public void setConfig(Config config) {
        VodConfig.load(config, lifecycleCallback(new Callback() {
            @Override
            public void start() {
                showProgress();
                hideContent();
                setTitle();
                setLogo();
            }

            @Override
            public void error(String msg) {
                Notify.dismiss();
                Notify.show(msg);
                showContent();
            }
        }));
    }

    @Override
    public void setSite(Site item) {
        VodConfig.get().setHome(item);
    }

    @Override
    public void onItemClick(int position, Class item) {
        mBinding.pager.setCurrentItem(position);
        mAdapter.setActivated(position);
    }

    @Override
    public void setFilter(String key, Value value) {
        getFragment().setFilter(key, value);
    }

    @Override
    public boolean canBack() {
        if (mBinding.pager.getAdapter() == null || mBinding.pager.getAdapter().getCount() == 0) return true;
        if (!getFragment().canBack()) return true;
        getFragment().goBack();
        return false;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        EventBus.getDefault().unregister(this);
    }

    private final ActivityResultLauncher<Intent> launcher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) return;
        if (getView() == null) return;
        FileChooser.importFile(getViewLifecycleOwner(), result.getData().getData(),
                path -> VideoActivity.file(requireActivity(), path));
    });

    @SuppressWarnings("deprecation")
    class PageAdapter extends FragmentStatePagerAdapter {

        public PageAdapter(@NonNull FragmentManager fm) {
            super(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT);
        }

        @NonNull
        @Override
        public Fragment getItem(int position) {
            Class type = mAdapter.get(position);
            return MobileFolderFragment.newInstance(getHome().getKey(), type, 4);
        }

        @Override
        public int getCount() {
            return mAdapter.getItemCount();
        }

        @Override
        public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
            super.destroyItem(container, position, object);
        }
    }
}
