package com.wuming.android.tv.ui.fragment;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.wuming.android.tv.model.SiteViewModel;
import com.wuming.android.tv.ui.CategoryFilterFixture;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class LeanbackCategoryFilterInstrumentedTest extends CategoryFilterFixture {
    @Override protected String folderClassName() {
        return "com.wuming.android.tv.ui.fragment.LeanbackFolderFragment";
    }
    @Override protected Fragment content(SiteViewModel model) {
        return new FixtureFragment(model);
    }
    @Override protected androidx.recyclerview.widget.RecyclerView.Adapter<?> tabs(
            java.util.List<com.wuming.android.tv.bean.Class> types,
            java.util.function.Consumer<com.wuming.android.tv.bean.Class> select) {
        com.wuming.android.tv.ui.adapter.LeanbackTypeAdapter adapter =
                new com.wuming.android.tv.ui.adapter.LeanbackTypeAdapter(
                        new com.wuming.android.tv.ui.adapter.LeanbackTypeAdapter.OnClickListener() {
                            @Override public void onItemClick(com.wuming.android.tv.bean.Class type) { select.accept(type); }
                            @Override public void onRefresh(com.wuming.android.tv.bean.Class type) { select.accept(type); }
                        });
        adapter.addAll(types);
        return adapter;
    }
    @Override protected void clickTab(androidx.recyclerview.widget.RecyclerView.ViewHolder holder) {
        org.junit.Assert.assertTrue(holder.itemView.performClick());
    }
    public static class FixtureFragment extends LeanbackTypeFragment {
        private final SiteViewModel model;
        public FixtureFragment(SiteViewModel model) { this.model = model; }
        @NonNull @Override public ViewModelProvider.Factory getDefaultViewModelProviderFactory() {
            return new ViewModelProvider.Factory() {
                @NonNull @Override public <T extends ViewModel> T create(@NonNull java.lang.Class<T> type) {
                    return type.cast(model);
                }
            };
        }
    }
}
