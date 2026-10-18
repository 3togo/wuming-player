package com.wuming.android.tv.ui.activity;

import com.wuming.android.tv.utils.TextScale;
import com.wuming.android.tv.setting.DisplaySettings;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.leanback.widget.HorizontalGridView;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.wuming.android.tv.R;
import com.wuming.android.tv.api.config.VodConfig;
import com.wuming.android.tv.bean.Site;
import com.wuming.android.tv.bean.Vod;
import com.wuming.android.tv.databinding.ActivityFastSearchBinding;
import com.wuming.android.tv.event.RefreshEvent;
import com.wuming.android.tv.model.SiteViewModel;
import com.wuming.android.tv.search.FastSearchCoordinator;
import com.wuming.android.tv.ui.adapter.LeanbackFastListAdapter;
import com.wuming.android.tv.ui.adapter.LeanbackFastSearchAdapter;
import com.wuming.android.tv.ui.adapter.SearchWordAdapter;
import com.wuming.android.tv.ui.base.BaseActivity;
import com.wuming.android.tv.ui.custom.EdgeFadeItemDecoration;
import com.wuming.android.tv.ui.custom.SpaceItemDecoration;
import com.wuming.android.tv.utils.SearchHelper;
import com.wuming.android.tv.utils.LayoutConfig;
import com.wuming.android.tv.utils.ResUtil;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import com.wuming.android.tv.utils.AppLog;

public class LeanbackFastSearchActivity extends BaseActivity {
    private static final String TAG = "LeanbackFastSearchActivity";

    private ActivityFastSearchBinding mBinding;
    private TextView mSearchTitle;
    private RecyclerView mGridView;
    private RecyclerView mGridViewFilter;
    private HorizontalGridView mGridViewWord;
    private RecyclerView mGridViewWordFenci;
    private SiteViewModel siteViewModel;

    private SearchWordAdapter searchWordAdapter;
    private LeanbackFastSearchAdapter searchAdapter;
    private LeanbackFastSearchAdapter searchAdapterFilter;
    private LeanbackFastListAdapter spListAdapter;
    private String searchTitle = "";
    private FastSearchCoordinator searchCoordinator;
    private int finishedCount = 0;
    private final List<String> quickSearchWord = new ArrayList<>();
    private HashMap<String, String> mCheckSources = null;

    private static class FocusChangeListener implements View.OnFocusChangeListener {
        private final WeakReference<LeanbackFastSearchActivity> activityRef;
        private final WeakReference<LeanbackFastListAdapter> adapterRef;
        FocusChangeListener(LeanbackFastSearchActivity activity, LeanbackFastListAdapter adapter) {
            this.activityRef = new WeakReference<>(activity);
            this.adapterRef = new WeakReference<>(adapter);
        }
        @Override
        public void onFocusChange(View itemView, boolean hasFocus) {
            LeanbackFastSearchActivity activity = activityRef.get();
            LeanbackFastListAdapter adapter = adapterRef.get();
            if (activity == null || adapter == null) return;
            try {
                if (!hasFocus) {
                    adapter.onLostFocus(itemView);
                } else {
                    int ret = adapter.onSetFocus(itemView);
                    if (ret < 0) return;
                    TextView v = (TextView) itemView;
                    activity.filterResult(v.getText().toString());
                }
            } catch (Exception e) {
                AppLog.e(TAG, e);
            }
        }
    }

    private static class SiteListAttachListener implements RecyclerView.OnChildAttachStateChangeListener {
        private final WeakReference<LeanbackFastSearchActivity> activityRef;
        private final WeakReference<View.OnFocusChangeListener> focusListenerRef;
        SiteListAttachListener(LeanbackFastSearchActivity activity, View.OnFocusChangeListener focusListener) {
            this.activityRef = new WeakReference<>(activity);
            this.focusListenerRef = new WeakReference<>(focusListener);
        }
        @Override
        public void onChildViewAttachedToWindow(@NonNull View child) {
            LeanbackFastSearchActivity activity = activityRef.get();
            View.OnFocusChangeListener focusListener = focusListenerRef.get();
            if (activity == null || focusListener == null) return;
            child.setFocusable(true);
            child.setOnFocusChangeListener(focusListener);
            TextView t = (TextView) child;
            if (t.getText().equals(activity.getString(R.string.fs_show_all))) {
                t.requestFocus();
            }
        }
        @Override
        public void onChildViewDetachedFromWindow(@NonNull View view) {
            view.setOnFocusChangeListener(null);
        }
    }

    private FocusChangeListener focusChangeListener;

    public static void start(Activity activity, String keyword) {
        Intent intent = new Intent(activity, LeanbackFastSearchActivity.class);
        intent.putExtra("title", keyword);
        activity.startActivity(intent);
    }

    private String getKeyword() {
        String keyword = getIntent().getStringExtra("title");
        return keyword != null ? keyword : "";
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityFastSearchBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        searchCoordinator = new FastSearchCoordinator();
        initViewWidgets();
        initViewModel();
        initData();
    }

    private void initViewWidgets() {
        mSearchTitle = mBinding.mSearchTitle;
        mGridView = mBinding.mGridView;
        mGridViewWord = mBinding.mGridViewWord;
        mGridViewFilter = mBinding.mGridViewFilter;
        mGridViewWordFenci = mBinding.mGridViewWordFenci;

        // Initialize search results count to 0
        mSearchTitle.setText(String.format(getString(R.string.grid_search_results), 0));
        TextScale.bind(mSearchTitle);

        mGridViewWord.setHasFixedSize(true);

        // Leanback (TV): site list should always be horizontal (top:site, bottom:content)
        // Force horizontal mode regardless of setting
        int siteListLayout = 1; // Always horizontal for leanback
        int targetItemHeight = LayoutConfig.getFastSearchSiteItemHeightPixels();
        if (siteListLayout == 1 || siteListLayout == 2) {
            // HorizontalGridView handles horizontal layout natively - no LinearLayoutManager needed
            mGridViewWord.setHorizontalSpacing(ResUtil.getDimensionPixelSize(R.dimen.vs_8));
            mGridViewWord.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
            // Add edge fade indicator for horizontal mode to show when content exceeds width
            mGridViewWord.addItemDecoration(new EdgeFadeItemDecoration(this));
            android.view.ViewGroup.LayoutParams wordParams = mBinding.llWord.getLayoutParams();
            if (wordParams instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) wordParams;
                lp.width = LinearLayout.LayoutParams.MATCH_PARENT;
                lp.height = siteListLayout == 2 ? targetItemHeight
                        : LayoutConfig.getTvSearchFilterRowHeightPixels();
                mBinding.llWord.setLayoutParams(lp);
            }
            // Change parent orientation to vertical for horizontal site list
            LinearLayout parent = (LinearLayout) mBinding.llWord.getParent();
            parent.setOrientation(LinearLayout.VERTICAL);
        } else {
            // Vertical mode: site list on left side (not used in leanback, but kept for compatibility)
            int targetWidth = LayoutConfig.getSiteColumnWidthPixels(this);
            mBinding.llWord.getLayoutParams().width = targetWidth;
            mBinding.llWord.setMinimumWidth(targetWidth);
            mBinding.mGridViewWord.getLayoutParams().width = targetWidth;
            mBinding.mGridViewWord.setMinimumWidth(targetWidth);
            mBinding.llWord.requestLayout();
            // Change parent orientation to horizontal for vertical site list
            LinearLayout parent = (LinearLayout) mBinding.llWord.getParent();
            parent.setOrientation(LinearLayout.HORIZONTAL);
        }

        spListAdapter = new LeanbackFastListAdapter();
        // Pass layout mode to adapter
        spListAdapter.setSiteListLayout(siteListLayout);
        spListAdapter.setTargetItemHeight(targetItemHeight);
        mGridViewWord.setAdapter(spListAdapter);

        mGridViewWord.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(@NonNull View child) {
                child.setFocusable(true);
                child.setOnFocusChangeListener(focusChangeListener);
                TextView t = (TextView) child;
                if (t.getText().equals(getString(R.string.fs_show_all))) {
                    t.requestFocus();
                }
            }

            @Override
            public void onChildViewDetachedFromWindow(@NonNull View view) {
                view.setOnFocusChangeListener(null);
            }
        });

        spListAdapter.setOnItemClickListener(item -> filterResult(item));

        // Set layout based on result layout setting: 0=List, 1=Horizontal, 2=Grid
        // For leanback, default to grid (5 columns) for better TV experience
        int layoutMode = (siteListLayout == 2) ? 2 : DisplaySettings.getSearchView();
        if (layoutMode == 0) layoutMode = 2; // Default to grid on TV
        if (layoutMode == 0) {
            mGridView.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
        } else if (layoutMode == 1) {
            mGridView.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
            // Add edge fade indicator for horizontal mode
            mGridView.addItemDecoration(new EdgeFadeItemDecoration(this));
        } else {
            mGridView.setLayoutManager(new GridLayoutManager(this, LayoutConfig.SEARCH_GRID_SPAN_COUNT_LEANBACK));
            mGridView.addItemDecoration(new SpaceItemDecoration(
                    LayoutConfig.SEARCH_GRID_SPAN_COUNT_LEANBACK, R.dimen.vs_16));
        }
        searchAdapter = new LeanbackFastSearchAdapter();
        mGridView.setAdapter(searchAdapter);
        searchAdapter.setOnItemClickListener(video -> {
            VideoActivity.start(this, video.getSite().getKey(), video.getId(), video.getName(), video.getPic());
        });

        if (layoutMode == 0) {
            mGridViewFilter.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
        } else if (layoutMode == 1) {
            mGridViewFilter.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
            // Add edge fade indicator for horizontal mode
            mGridViewFilter.addItemDecoration(new EdgeFadeItemDecoration(this));
        } else {
            mGridViewFilter.setLayoutManager(new GridLayoutManager(this, LayoutConfig.SEARCH_GRID_SPAN_COUNT_LEANBACK));
            mGridViewFilter.addItemDecoration(new SpaceItemDecoration(
                    LayoutConfig.SEARCH_GRID_SPAN_COUNT_LEANBACK, R.dimen.vs_16));
        }
        searchAdapterFilter = new LeanbackFastSearchAdapter();
        mGridViewFilter.setAdapter(searchAdapterFilter);
        searchAdapterFilter.setOnItemClickListener(video -> {
            VideoActivity.start(this, video.getSite().getKey(), video.getId(), video.getName(), video.getPic());
        });

        // Word segmentation
        searchWordAdapter = new SearchWordAdapter();
        mGridViewWordFenci.setAdapter(searchWordAdapter);
        mGridViewWordFenci.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        // Add edge fade indicator for word segmentation horizontal list
        mGridViewWordFenci.addItemDecoration(new EdgeFadeItemDecoration(this));
        searchWordAdapter.setOnItemClickListener(this::search);
        searchWordAdapter.setNewData(new ArrayList<>());
    }

    private void initViewModel() {
        siteViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        siteViewModel.getSearch().observe(this, result -> {
            if (result != null && !result.getList().isEmpty()) {
                searchData(result.getList());
            }
        });
    }

    private void filterResult(String spName) {
        if (spName.equals(getString(R.string.fs_show_all))) {
            searchCoordinator.showAllSources();
            mGridView.setVisibility(View.VISIBLE);
            mGridViewFilter.setVisibility(View.GONE);
            return;
        }
        List<Vod> list = searchCoordinator.selectSource(spName);
        if (list == null) return;
        searchAdapterFilter.setNewData(list);
        mGridView.setVisibility(View.GONE);
        mGridViewFilter.setVisibility(View.VISIBLE);
        mGridViewFilter.requestLayout();
    }

    private void fenci() {
        if (!quickSearchWord.isEmpty()) return;
        OkHttp.newCall("https://api.yesapi.cn/?service=App.Scws.GetWords&text=" + searchTitle + "&app_key=CEE4B8A091578B252AC4C92FB4E893C3&sign=CB7602F3AC922808AF5D475D8DA33302")
                .enqueue(new com.wuming.android.tv.impl.Callback() {
                    @Override
                    public void onResponse(@NonNull okhttp3.Call call, @NonNull okhttp3.Response response) {
                        try {
                            String json = response.body().string();
                            quickSearchWord.clear();
                            try {
                                JsonObject resJson = JsonParser.parseString(json).getAsJsonObject();
                                JsonElement wordsJson = resJson.get("data").getAsJsonObject().get("words");

                                for (JsonElement je : wordsJson.getAsJsonArray()) {
                                    quickSearchWord.add(je.getAsJsonObject().get("word").getAsString());
                                }
                            } catch (Throwable th) {
                                AppLog.e(TAG, th);
                            }
                            quickSearchWord.add(searchTitle);
                            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.Type.QUICK_SEARCH_WORD, quickSearchWord));
                        } catch (Exception e) {
                            AppLog.e(TAG, e);
                        }
                    }
                });
    }

    private void initCheckedSourcesForSearch() {
        mCheckSources = SearchHelper.getSourcesForSearch();
    }

    private void initData() {
        initCheckedSourcesForSearch();
        String title = getKeyword();
        if (!title.isEmpty()) {
            search(title);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void refresh(RefreshEvent event) {
        if (mSearchTitle != null) {
            finishedCount = searchAdapter.getData().size();
            mSearchTitle.setText(String.format(getString(R.string.grid_search_results), finishedCount));
        }
        if (event.getType() == RefreshEvent.Type.QUICK_SEARCH_WORD) {
            if (event.getObj() != null) {
                @SuppressWarnings("unchecked")
                List<String> data = (List<String>) event.getObj();
                searchWordAdapter.setNewData(data);
            }
        }
    }

    private void search(String title) {
        this.searchTitle = title;
        fenci();
        mGridView.setVisibility(View.INVISIBLE);
        mGridViewFilter.setVisibility(View.GONE);
        searchAdapter.setNewData(new ArrayList<>());
        searchAdapterFilter.setNewData(new ArrayList<>());

        spListAdapter.reset();
        searchCoordinator.reset();
        searchCoordinator.setSearchQuery(title);

        // Reset search results count to 0
        finishedCount = 0;
        mSearchTitle.setText(String.format(getString(R.string.grid_search_results), 0));

        searchResult();
    }

    private void searchResult() {
        List<Site> searchRequestList = searchCoordinator.begin(
                VodConfig.get().getSites(), mCheckSources);

        // Clear site list - will be populated as results come in
        spListAdapter.setNewData(new ArrayList<>());
        spListAdapter.addData(getString(R.string.fs_show_all));

        siteViewModel.searchContent(searchRequestList, searchTitle, false);
    }

    private String addWordAdapterIfNeed(String key) {
        try {
            String name = searchCoordinator.sourceName(key);
            if (name.isEmpty()) return key;

            List<String> names = spListAdapter.getData();
            if (names.contains(name)) return key;
            spListAdapter.addData(searchCoordinator.sourceInsertionPosition(key, names), name, key);

            return key;
        } catch (Exception e) {
            return key;
        }
    }

    private void searchData(List<Vod> vodList) {
        if (vodList == null || vodList.isEmpty()) return;

        for (String sourceKey : searchCoordinator.add(vodList)) addWordAdapterIfNeed(sourceKey);
        
        // Always add data, never replace
        if (searchAdapter.getData().isEmpty()) {
            mGridView.setVisibility(View.VISIBLE);
        }
        
        searchAdapter.addData(vodList);
        searchAdapter.sort(searchCoordinator.rankingComparator());

        // Update result count
        if (mSearchTitle != null) {
            int count = searchAdapter.getData().size();
            mSearchTitle.setText(String.format(getString(R.string.grid_search_results), count));
        }
    }
    
    @Override
    protected void onDestroy() {
        if (siteViewModel != null) {
            siteViewModel.stopSearch();
            siteViewModel.getSearch().removeObservers(this);
            siteViewModel = null;
        }
        super.onDestroy();
    }
}