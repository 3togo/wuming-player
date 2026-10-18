package com.wuming.android.tv.ui.activity;

import com.wuming.android.tv.utils.TextScale;
import com.wuming.android.tv.setting.DisplaySettings;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.wuming.android.tv.R;
import com.wuming.android.tv.MobileProduct;
import com.wuming.android.tv.api.config.VodConfig;
import com.wuming.android.tv.bean.Site;
import com.wuming.android.tv.bean.Vod;
import com.wuming.android.tv.databinding.ActivityFastSearchBinding;
import com.wuming.android.tv.event.RefreshEvent;
import com.wuming.android.tv.model.SiteViewModel;
import com.wuming.android.tv.search.FastSearchCoordinator;
import com.wuming.android.tv.ui.adapter.MobileFastListAdapter;
import com.wuming.android.tv.ui.adapter.MobileFastSearchAdapter;
import com.wuming.android.tv.ui.adapter.SearchWordAdapter;
import com.wuming.android.tv.ui.base.BaseActivity;
import com.wuming.android.tv.ui.custom.EdgeFadeItemDecoration;
import com.wuming.android.tv.utils.SearchHelper;
import com.wuming.android.tv.utils.LayoutConfig;
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

public class MobileFastSearchActivity extends BaseActivity {
    private static final String TAG = "MobileFastSearchActivity";

    private ActivityFastSearchBinding mBinding;
    private TextView mSearchTitle;
    private RecyclerView mGridView;
    private RecyclerView mGridViewFilter;
    private RecyclerView mGridViewWord;
    private RecyclerView mGridViewWordFenci;
    private SiteViewModel siteViewModel;

    private SearchWordAdapter searchWordAdapter;
    private MobileFastSearchAdapter searchAdapter;
    private MobileFastSearchAdapter searchAdapterFilter;
    private MobileFastListAdapter spListAdapter;
    private String searchTitle = "";
    private FastSearchCoordinator searchCoordinator;
    private int finishedCount = 0;
    private final List<String> quickSearchWord = new ArrayList<>();
    private HashMap<String, String> mCheckSources = null;

    private static class FocusChangeListener implements View.OnFocusChangeListener {
        private final WeakReference<MobileFastSearchActivity> activityRef;
        private final WeakReference<MobileFastListAdapter> adapterRef;
        FocusChangeListener(MobileFastSearchActivity activity, MobileFastListAdapter adapter) {
            this.activityRef = new WeakReference<>(activity);
            this.adapterRef = new WeakReference<>(adapter);
        }
        @Override
        public void onFocusChange(View itemView, boolean hasFocus) {
            MobileFastSearchActivity activity = activityRef.get();
            MobileFastListAdapter adapter = adapterRef.get();
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
        private final WeakReference<MobileFastSearchActivity> activityRef;
        private final WeakReference<View.OnFocusChangeListener> focusListenerRef;
        SiteListAttachListener(MobileFastSearchActivity activity, View.OnFocusChangeListener focusListener) {
            this.activityRef = new WeakReference<>(activity);
            this.focusListenerRef = new WeakReference<>(focusListener);
        }
        @Override
        public void onChildViewAttachedToWindow(@NonNull View child) {
            MobileFastSearchActivity activity = activityRef.get();
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

    public static void start(Activity activity) {
        // When no keyword provided, launch MobileSearchActivity instead (has input field)
        MobileSearchActivity.start(activity);
    }

    public static void start(Activity activity, String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            // Empty keyword, launch MobileSearchActivity instead (has input field)
            MobileSearchActivity.start(activity);
        } else {
            Intent intent = new Intent(activity, MobileFastSearchActivity.class);
            intent.putExtra("title", keyword);
            activity.startActivity(intent);
        }
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
        TextScale.bind(mSearchTitle);

        mGridViewWord.setHasFixedSize(true);

        // Set site list orientation and layout based on setting
        // 0 = Vertical left, 1 = Horizontal top, 2 = Compact
        int siteListLayout = DisplaySettings.getSiteListLayout();
        if (siteListLayout == 1 || siteListLayout == 2) {
            // Horizontal/Compact mode: site list at top, full width
            mGridViewWord.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
            // Add edge fade indicator for horizontal mode to show when content exceeds width
            mGridViewWord.addItemDecoration(new EdgeFadeItemDecoration(this));
            android.view.ViewGroup.LayoutParams wordParams = mBinding.llWord.getLayoutParams();
            if (wordParams instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) wordParams;
                lp.width = LinearLayout.LayoutParams.MATCH_PARENT;
                lp.height = LayoutConfig.getMobileSearchSiteStripHeightPixels(this);
                mBinding.llWord.setLayoutParams(lp);
            }
            // Change parent orientation to vertical for horizontal site list
            LinearLayout parent = (LinearLayout) mBinding.llWord.getParent();
            parent.setOrientation(LinearLayout.VERTICAL);
        } else {
            // Vertical mode: site list on left side
            mGridViewWord.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
            int targetWidth = LayoutConfig.getSiteColumnWidthPixels(this);
            int railWidth = LayoutConfig.getSiteRailWidthPixels(this, mBinding.llWord);
            mBinding.llWord.getLayoutParams().width = targetWidth;
            mBinding.llWord.setMinimumWidth(targetWidth);
            mBinding.mGridViewWord.getLayoutParams().width = railWidth;
            mBinding.mGridViewWord.setMinimumWidth(railWidth);
            mBinding.llWord.requestLayout();
            // Change parent orientation to horizontal for vertical site list
            LinearLayout parent = (LinearLayout) mBinding.llWord.getParent();
            parent.setOrientation(LinearLayout.HORIZONTAL);
        }

        spListAdapter = new MobileFastListAdapter();
        // Pass layout mode to adapter
        spListAdapter.setSiteListLayout(siteListLayout);
        mGridViewWord.setAdapter(spListAdapter);

        focusChangeListener = new FocusChangeListener(this, spListAdapter);
        mGridViewWord.addOnChildAttachStateChangeListener(new SiteListAttachListener(this, focusChangeListener));

        spListAdapter.setOnItemClickListener(item -> filterResult(item));

        // Set layout based on result layout setting: 0=List, 1=Horizontal, 2=Grid
        // For two-column vertical layout (siteListLayout==0) and compact mode (siteListLayout==2),
        // always use GridLayoutManager — 1 col in portrait, 5 cols in landscape.
        int layoutMode;
        if (siteListLayout == 0 || siteListLayout == 2) {
            layoutMode = 2;
        } else {
            layoutMode = DisplaySettings.getSearchView();
        }
        if (layoutMode == 0) {
            mGridView.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
        } else if (layoutMode == 1) {
            mGridView.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
            // Add edge fade indicator for horizontal mode
            mGridView.addItemDecoration(new EdgeFadeItemDecoration(this));
        } else {
            mGridView.setLayoutManager(new GridLayoutManager(this, LayoutConfig.getSearchGridSpanCount(this)));
        }
        searchAdapter = new MobileFastSearchAdapter();
        int initialSpan = LayoutConfig.getSearchGridSpanCount(this);
        int leftColW = (siteListLayout == 0) ? LayoutConfig.getSiteColumnWidthPixels(this) : 0;
        int[] initialSize = MobileProduct.getSpecWithReservedWidth(this, leftColW, initialSpan);
        searchAdapter.setSize(initialSize);
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
            mGridViewFilter.setLayoutManager(new GridLayoutManager(this, LayoutConfig.getSearchGridSpanCount(this)));
        }
        searchAdapterFilter = new MobileFastSearchAdapter();
        searchAdapterFilter.setSize(initialSize);
        mGridViewFilter.setAdapter(searchAdapterFilter);
        searchAdapterFilter.setOnItemClickListener(video -> {
            VideoActivity.start(this, video.getSite().getKey(), video.getId(), video.getName(), video.getPic());
        });
        // The provider rail and system insets make screen-width estimates
        // inaccurate. Recalculate from the actual result viewport once laid out.
        mBinding.llLayout.post(this::updateResultGridGeometry);

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

        searchResult();
    }

    private void searchResult() {
        try {
            List<Site> searchRequestList = searchCoordinator.begin(
                    VodConfig.get().getSites(), mCheckSources);

            spListAdapter.setNewData(new ArrayList<>());
            spListAdapter.addData(getString(R.string.fs_show_all));

            siteViewModel.searchContent(searchRequestList, searchTitle, false);
        } catch (Exception e) {
            AppLog.e(TAG, "Error in searchResult: " + e.getMessage(), e);
        }
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
        mBinding = null;
        spListAdapter = null;
        searchAdapter = null;
        searchAdapterFilter = null;
        searchWordAdapter = null;
        searchCoordinator = null;
        mCheckSources = null;
        super.onDestroy();
    }

    @Override
    protected void onOrientationChanged(Configuration newConfig) {
        if (mBinding == null) return;
        TextScale.bind(mSearchTitle);
        updateGridSpanCounts();
        updateLayoutDimensions();
    }

    private void updateGridSpanCounts() {
        int siteListLayout = DisplaySettings.getSiteListLayout();
        int layoutMode;
        if (siteListLayout == 0 || siteListLayout == 2) {
            layoutMode = 2;
        } else {
            layoutMode = DisplaySettings.getSearchView();
        }
        int spanCount = LayoutConfig.getSearchGridSpanCount(this);

        // Update mGridView — swap LayoutManager type if needed
        if (layoutMode == 2) {
            RecyclerView.LayoutManager current = mGridView.getLayoutManager();
            if (current instanceof GridLayoutManager) {
                ((GridLayoutManager) current).setSpanCount(spanCount);
            } else {
                GridLayoutManager gm = new GridLayoutManager(this, spanCount);
                mGridView.setLayoutManager(gm);
            }
        } else if (layoutMode == 1) {
            mGridView.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        } else {
            mGridView.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
        }

        // Update mGridViewFilter similarly
        if (layoutMode == 2) {
            RecyclerView.LayoutManager current = mGridViewFilter.getLayoutManager();
            if (current instanceof GridLayoutManager) {
                ((GridLayoutManager) current).setSpanCount(spanCount);
            } else {
                GridLayoutManager gm = new GridLayoutManager(this, spanCount);
                mGridViewFilter.setLayoutManager(gm);
            }
        } else if (layoutMode == 1) {
            mGridViewFilter.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        } else {
            mGridViewFilter.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
        }
        mBinding.llLayout.post(this::updateResultGridGeometry);
    }

    private void updateResultGridGeometry() {
        if (mBinding == null || searchAdapter == null || searchAdapterFilter == null) return;
        if (!(mGridView.getLayoutManager() instanceof GridLayoutManager manager)) return;
        int viewportWidth = mGridView.getWidth()
                - mGridView.getPaddingStart()
                - mGridView.getPaddingEnd();
        if (viewportWidth <= 0) return;
        int[] size = MobileProduct.getSpecForViewport(this, viewportWidth, manager.getSpanCount());
        searchAdapter.setSize(size);
        searchAdapterFilter.setSize(size);
    }

    private void updateLayoutDimensions() {
        int siteListLayout = DisplaySettings.getSiteListLayout();

        if (siteListLayout == 1 || siteListLayout == 2) {
            android.view.ViewGroup.LayoutParams wordParams = mBinding.llWord.getLayoutParams();
            if (wordParams instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) wordParams;
                lp.height = LayoutConfig.getMobileSearchSiteStripHeightPixels(this);
                mBinding.llWord.setLayoutParams(lp);
            }
        } else {
            int targetWidth = LayoutConfig.getSiteColumnWidthPixels(this);
            int railWidth = LayoutConfig.getSiteRailWidthPixels(this, mBinding.llWord);
            mBinding.llWord.getLayoutParams().width = targetWidth;
            mBinding.llWord.setMinimumWidth(targetWidth);
            mBinding.mGridViewWord.getLayoutParams().width = railWidth;
            mBinding.mGridViewWord.setMinimumWidth(railWidth);
            mBinding.llWord.requestLayout();
        }
    }
}