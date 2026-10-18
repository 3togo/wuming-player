package com.wuming.android.tv.ui.activity;

import com.wuming.android.tv.R;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.leanback.widget.OnChildViewHolderSelectedListener;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;
import androidx.viewpager.widget.ViewPager;

import com.wuming.android.tv.App;
import com.wuming.android.tv.api.config.VodConfig;
import com.wuming.android.tv.bean.Class;
import com.wuming.android.tv.bean.Result;
import com.wuming.android.tv.databinding.ActivityVodBinding;
import com.wuming.android.tv.event.RefreshEvent;
import com.wuming.android.tv.ui.adapter.LeanbackTypeAdapter;
import com.wuming.android.tv.ui.base.BaseActivity;
import com.wuming.android.tv.ui.fragment.LeanbackFolderFragment;
import com.wuming.android.tv.ui.custom.GridFocusRestorer;
import com.wuming.android.tv.utils.BundleUtil;
import com.wuming.android.tv.utils.KeyUtil;
import com.wuming.android.tv.utils.ResUtil;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class VodActivity extends BaseActivity implements LeanbackTypeAdapter.OnClickListener {

    private ActivityVodBinding mBinding;
    private LeanbackTypeAdapter mAdapter;
    private View mOldView;

    public static void start(Activity activity, Result result) {
        start(activity, VodConfig.get().getHome().getKey(), result);
    }

    public static void start(Activity activity, String key, Result result) {
        if (result == null) return;
        if (result.getTypes().isEmpty() && result.getList().isEmpty()) return;
        Intent intent = new Intent(activity, VodActivity.class);
        intent.putExtra("key", key);
        intent.putExtra("result", result);
        activity.startActivity(intent);
    }

    private String getKey() {
        return getIntent().getStringExtra("key");
    }

    private Result getResult() {
        return BundleUtil.getParcelableExtra(getIntent(), "result", Result.class);
    }

    private Class getType() {
        return mAdapter.get(mBinding.pager.getCurrentItem());
    }

    private LeanbackFolderFragment getFragment() {
        return (LeanbackFolderFragment) mBinding.pager.getAdapter().instantiateItem(mBinding.pager, mBinding.pager.getCurrentItem());
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityVodBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mRunnable = new PagerUpdateRunnable(this);
        setRecyclerView();
        setTypes();
        setPager();
    }

    @Override
    protected void initEvent() {
        mBinding.pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                GridFocusRestorer.restoreAfterLayout(mBinding.recycler, position);
            }
        });
        mBinding.recycler.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                onChildSelected(child);
            }
        });
    }

    private void setRecyclerView() {
        mBinding.recycler.requestFocus();
        mBinding.recycler.setHorizontalSpacing(ResUtil.getDimensionPixelSize(R.dimen.vs_16));
        mBinding.recycler.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.recycler.setAdapter(mAdapter = new LeanbackTypeAdapter(this));
    }

    private Class home() {
        Class type = new Class();
        type.setTypeName(ResUtil.getString(R.string.vod_home));
        type.setTypeId("home");
        return type;
    }

    private List<Class> getTypes() {
        Result result = getResult();
        List<Class> types = new ArrayList<>(result.getTypes());
        if (!result.getList().isEmpty()) types.add(0, home());
        return types;
    }

    private void setTypes() {
        mAdapter.addAll(getTypes());
    }

    private void setPager() {
        mBinding.pager.setAdapter(new PageAdapter(getSupportFragmentManager()));
    }

    private void onChildSelected(@Nullable RecyclerView.ViewHolder child) {
        if (mOldView != null) mOldView.setActivated(false);
        if ((mOldView = child != null ? child.itemView : null) == null) return;
        mOldView.setActivated(true);
        App.post(mRunnable, 100);
    }

    private static class PagerUpdateRunnable implements Runnable {
        private final WeakReference<VodActivity> activityRef;
        PagerUpdateRunnable(VodActivity activity) {
            this.activityRef = new WeakReference<>(activity);
        }
        @Override
        public void run() {
            VodActivity activity = activityRef.get();
            if (activity != null && activity.mBinding != null) {
                activity.mBinding.pager.setCurrentItem(activity.mBinding.recycler.getSelectedPosition());
            }
        }
    }

    private PagerUpdateRunnable mRunnable;

    private boolean isFilterVisible() {
        return Optional.ofNullable(getType()).map(Class::getFilter).orElse(false);
    }

    private void updateFilter() {
        Optional.ofNullable(getType()).ifPresent(this::updateFilter);
    }

    private void updateFilter(Class item) {
        selectType(item);
        item.setFilter(!item.getFilter());
        getFragment().toggleFilter(item.getFilter());
        mAdapter.notifyItemRangeChanged(mAdapter.indexOf(item), 1);
    }

    private void selectType(Class item) {
        int position = mAdapter.indexOf(item);
        if (position < 0) return;
        App.removeCallbacks(mRunnable);
        if (mBinding.pager.getCurrentItem() != position) {
            mBinding.pager.setCurrentItem(position);
        }
    }

    private void refresh(Class item) {
        selectType(item);
        getFragment().onRefresh();
    }

    public void closeFilter() {
        if (isFilterVisible()) updateFilter();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (event.getType() == RefreshEvent.Type.CATEGORY) getFragment().onRefresh();
    }

    @Override
    public void onItemClick(Class item) {
        updateFilter(item);
        refresh(item);
    }

    @Override
    public void onRefresh(Class item) {
        refresh(item);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (KeyUtil.isMenuKey(event)) updateFilter();
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onBackInvoked() {
        if (isFilterVisible()) updateFilter();
        else if (getFragment().canBack()) getFragment().goBack();
        else super.onBackInvoked();
    }

    @SuppressWarnings("deprecation")
    class PageAdapter extends FragmentStatePagerAdapter {

        public PageAdapter(@NonNull FragmentManager fm) {
            super(fm);
        }

        @NonNull
        @Override
        public Fragment getItem(int position) {
            Class type = mAdapter.get(position);
            return LeanbackFolderFragment.newInstance(getKey(), type, type.isHome() ? getResult() : null);
        }

        @Override
        public int getCount() {
            return mAdapter.getItemCount();
        }

        @Override
        public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
        }
    }

    @Override
    protected void onDestroy() {
        App.removeCallbacks(mRunnable);
        mBinding = null;
        mAdapter = null;
        mOldView = null;
        super.onDestroy();
    }
}
