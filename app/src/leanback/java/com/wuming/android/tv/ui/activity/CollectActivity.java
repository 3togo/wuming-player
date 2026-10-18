package com.wuming.android.tv.ui.activity;

import com.wuming.android.tv.utils.TextScale;
import com.wuming.android.tv.setting.DisplaySettings;
import com.wuming.android.tv.setting.AppSettings;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcelable;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.leanback.widget.OnChildViewHolderSelectedListener;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;
import androidx.viewpager.widget.ViewPager;

import com.wuming.android.tv.App;
import com.wuming.android.tv.R;
import com.wuming.android.tv.api.config.VodConfig;
import com.wuming.android.tv.api.config.SiteSourceAdapter;
import com.wuming.android.tv.bean.Collect;
import com.wuming.android.tv.bean.SearchFilter;
import com.wuming.android.tv.bean.Site;
import com.wuming.android.tv.databinding.ActivityCollectBinding;
import com.wuming.android.tv.model.SiteViewModel;
import com.wuming.android.tv.ui.adapter.LeanbackCollectAdapter;
import com.wuming.android.tv.ui.base.BaseActivity;
import com.wuming.android.tv.ui.fragment.LeanbackCollectFragment;
import com.wuming.android.tv.ui.custom.GridFocusRestorer;
import com.wuming.android.tv.utils.LayoutConfig;
import com.wuming.android.tv.utils.SiteRanker;
import com.google.gson.reflect.TypeToken;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

public class CollectActivity extends BaseActivity implements LeanbackCollectAdapter.OnClickListener {

    private ActivityCollectBinding mBinding;
    private LeanbackCollectAdapter mAdapter;
    private SiteViewModel mViewModel;
    private List<Site> mSites;
    private View mOldView;
    private SearchFilter mFilter;

    public static void start(Activity activity, String keyword) {
        Intent intent = new Intent(activity, CollectActivity.class);
        intent.putExtra("keyword", keyword);
        activity.startActivity(intent);
    }

    private LeanbackCollectFragment getFragment() {
        return (LeanbackCollectFragment) mBinding.pager.getAdapter().instantiateItem(mBinding.pager, 0);
    }

    private String getKeyword() {
        return getIntent().getStringExtra("keyword");
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityCollectBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        getIntent().putExtras(intent);
        mAdapter.clear();
        setPager();
        search();
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mFilter = new SearchFilter();
        setRecyclerView();
        setViewModel();
        saveKeyword();
        setSites();
        setPager();
        updateStrictSearch();
        search();
        mBinding.recycler.requestFocus();
    }

    @Override
    protected void initEvent() {
        mBinding.strictSearch.setOnClickListener(v -> toggleStrictSearch());
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

    private void toggleStrictSearch() {
        DisplaySettings.putStrictSearch(!DisplaySettings.isStrictSearch());
        updateStrictSearch();
        mViewModel.stopSearch();
        mBinding.pager.setAdapter(null);
        androidx.fragment.app.FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        for (Fragment fragment : getSupportFragmentManager().getFragments()) {
            if (fragment instanceof LeanbackCollectFragment) transaction.remove(fragment);
        }
        transaction.commitNowAllowingStateLoss();
        mAdapter.clear();
        setPager();
        search();
        mBinding.strictSearch.requestFocus();
    }

    private void updateStrictSearch() {
        boolean enabled = DisplaySettings.isStrictSearch();
        mBinding.strictSearch.setText(enabled ? R.string.search_strict_on : R.string.search_strict_off);
        mBinding.strictSearch.setActivated(enabled);
    }

    private void setRecyclerView() {
        TextScale.bind(mBinding.result);
        mBinding.recycler.setHorizontalSpacing(0);
        mBinding.recycler.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.recycler.setAdapter(mAdapter = new LeanbackCollectAdapter(this));
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getSearch().observe(this, result -> {
            if (result.getList().isEmpty()) return;
            getFragment().addVideo(result.getList());
            
            // Insert in the correct position based on sorted site order
            String siteKey = result.getList().get(0).getSite().getKey();
            
            // Find this site's position in the sorted list
            int myPosition = -1;
            for (int i = 0; i < mSites.size(); i++) {
                if (mSites.get(i).getKey().equals(siteKey)) {
                    myPosition = i;
                    break;
                }
            }
            
            if (myPosition < 0) {
                // Site not found in sorted list, append at the end
                android.util.Log.d("CollectActivity", "Site not found in sorted list: " + siteKey + ", appending at end");
                mAdapter.add(Collect.create(result.getList()));
                mBinding.pager.getAdapter().notifyDataSetChanged();
                return;
            }
            
            // Find the correct insertion position
            // Start at position 1 (after "All")
            int insertPosition = 1;
            
            // Check all sites before this one in the sorted list
            for (int i = 0; i < myPosition; i++) {
                Site siteBefore = mSites.get(i);
                // Check if this site is already in the adapter
                boolean found = false;
                for (int j = 1; j < mAdapter.getItemCount(); j++) {
                    Collect collect = mAdapter.getItems().get(j);
                    if (collect.getSite().getKey().equals(siteBefore.getKey())) {
                        found = true;
                        break;
                    }
                }
                if (found) {
                    insertPosition++;
                }
            }
            
            android.util.Log.d("CollectActivity", "Inserting site: " + siteKey + " at position " + insertPosition + " (sorted position " + myPosition + ", list size " + mAdapter.getItemCount() + ")");
            mAdapter.add(insertPosition, Collect.create(result.getList()));
            mBinding.pager.getAdapter().notifyDataSetChanged();
        });
    }

    private void saveKeyword() {
        List<String> items = AppSettings.getKeyword().isEmpty() ? new ArrayList<>() : App.gson().fromJson(AppSettings.getKeyword(), new TypeToken<List<String>>() {}.getType());
        items.remove(getKeyword());
        items.add(0, getKeyword());
        if (items.size() > 9) items.remove(9);
        AppSettings.putKeyword(App.gson().toJson(items));
    }

    private void setSites() {
        List<Site> searchableSites = new ArrayList<>(
                SiteSourceAdapter.catalog().searchable(VodConfig.get().getSites(), null));
        mSites = SiteRanker.get().rankByPerformance(searchableSites);
    }

    private void setPager() {
        mBinding.pager.setAdapter(new PageAdapter(getSupportFragmentManager(), this, mAdapter.getItems()));
    }

    private void search() {
        if (mSites.isEmpty()) return;
        mAdapter.add(Collect.all());
        mBinding.pager.getAdapter().notifyDataSetChanged();
        mBinding.result.setText(getString(R.string.collect_result, getKeyword()));
        mViewModel.searchContent(mSites, getKeyword(), false);
    }

    private void cycleSort() {
        SearchFilter.Sort[] sorts = SearchFilter.Sort.values();
        int next = (mFilter.getSort().ordinal() + 1) % sorts.length;
        mFilter.setSort(sorts[next]);
        mBinding.result.setText(getString(R.string.collect_result, getKeyword()) + "  [" + mFilter.getSortLabel() + "]");
        getFragment().sortResults(mFilter, getKeyword());
    }

    private void onChildSelected(@Nullable RecyclerView.ViewHolder child) {
        if (mOldView != null) mOldView.setActivated(false);
        if ((mOldView = child != null ? child.itemView : null) == null) return;
        mOldView.setActivated(true);
    }

    @Override
    public void onItemClick(int position, Collect item) {
        mBinding.pager.setCurrentItem(position);
        mBinding.recycler.setSelectedPosition(position);
        mBinding.recycler.requestFocus();
    }

    @Override
    protected void onBackInvoked() {
        mViewModel.stopSearch();
        super.onBackInvoked();
    }

    @Override
    protected void onDestroy() {
        if (mViewModel != null) {
            mViewModel.stopSearch();
            mViewModel.getSearch().removeObservers(this);
            mViewModel = null;
        }
        mBinding = null;
        mAdapter = null;
        mSites = null;
        mOldView = null;
        mFilter = null;
        super.onDestroy();
    }

    // CustomViewPager still uses the legacy ViewPager contract. Keep this
    // adapter until the pager and its retained-fragment behavior migrate
    // together to ViewPager2.
    @SuppressWarnings("deprecation")
    private static class PageAdapter extends androidx.fragment.app.FragmentPagerAdapter {

        private final WeakReference<CollectActivity> activityRef;
        private final List<Collect> items;

        public PageAdapter(@NonNull FragmentManager fm, CollectActivity activity, List<Collect> items) {
            super(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT);
            this.activityRef = new WeakReference<>(activity);
            this.items = items;
        }

        @NonNull
        @Override
        public Fragment getItem(int position) {
            CollectActivity activity = activityRef.get();
            if (activity == null) return new Fragment();
            return LeanbackCollectFragment.newInstance(activity.getKeyword(), items.get(position));
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
        }

        @Nullable
        @Override
        public Parcelable saveState() {
            return null;
        }

        @Override
        public void restoreState(@Nullable Parcelable state, @Nullable ClassLoader loader) {
        }
    }
}
