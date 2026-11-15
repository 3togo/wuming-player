package com.wuming.android.tv.ui;

import com.wuming.android.tv.ui.fragment.LeanbackFolderFragment;
import com.wuming.android.tv.ui.fragment.LeanbackTypeFragment;

import static org.junit.Assert.*;

import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentFactory;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.gson.Gson;
import com.wuming.android.tv.R;
import com.wuming.android.tv.bean.FilterCache;
import com.wuming.android.tv.bean.Result;
import com.wuming.android.tv.model.SiteViewModel;
import com.wuming.android.tv.ui.activity.MemoryTestActivity;
import java.util.HashMap;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class LeanbackFilterLifecycleInstrumentedTest {
    @Test public void earlyTabClickSurvivesLazyInitializationEmptyResultsAndViewRecreation() {
        for (String id : new String[]{"movie", "tv"}) {
            com.wuming.android.tv.bean.Class type = new Gson().fromJson(
                    "{\"type_id\":\"" + id + "\",\"filters\":[{\"key\":\"year\",\"value\":[{\"n\":\"All years\",\"v\":\"\"}]}]}",
                    com.wuming.android.tv.bean.Class.class);
            Result result = new Result();
            result.setTypes(List.of(type));
            FilterCache.clear().put(result);
            try (ActivityScenario<MemoryTestActivity> scenario = TestActivityLauncher.launch(MemoryTestActivity.class)) {
                scenario.onActivity(activity -> {
                    FrameLayout container = new FrameLayout(activity);
                    container.setId(View.generateViewId());
                    ((ViewGroup) activity.findViewById(android.R.id.custom)).addView(container,
                            new ViewGroup.LayoutParams(-1, -1));
                    LeanbackFolderFragment folder = LeanbackFolderFragment.newInstance("filter-fixture", type);
                    // Match a click before ViewPager's transaction has attached the page.
                    folder.toggleFilter(true);
                    folder.onRefresh();
                    activity.getSupportFragmentManager().beginTransaction().add(container.getId(), folder)
                            .setMaxLifecycle(folder, Lifecycle.State.CREATED).commitNow();
                    folder.getChildFragmentManager().setFragmentFactory(new FragmentFactory() {
                        @NonNull @Override public Fragment instantiate(@NonNull ClassLoader loader, @NonNull String name) {
                            return name.equals(LeanbackTypeFragment.class.getName()) ? new EmptyPage() : super.instantiate(loader, name);
                        }
                    });
                    activity.getSupportFragmentManager().beginTransaction()
                            .setMaxLifecycle(folder, Lifecycle.State.STARTED).commitNow();
                    folder.toggleFilter(true);
                    folder.onRefresh();
                    activity.getSupportFragmentManager().beginTransaction()
                            .setMaxLifecycle(folder, Lifecycle.State.RESUMED).commitNow();
                    folder.getChildFragmentManager().executePendingTransactions();
                    assertRows(folder, 1);
                    folder.toggleFilter(true);
                    folder.onRefresh();
                    assertRows(folder, 1);
                    folder.toggleFilter(false);
                    folder.toggleFilter(false);
                    assertRows(folder, 0);
                    folder.toggleFilter(true);
                    activity.getSupportFragmentManager().beginTransaction().detach(folder).commitNow();
                    activity.getSupportFragmentManager().beginTransaction().attach(folder).commitNow();
                    folder.getChildFragmentManager().executePendingTransactions();
                    assertRows(folder, 1);
                });
            } finally { FilterCache.clear(); }
        }
    }

    private static void assertRows(LeanbackFolderFragment folder, int count) {
        RecyclerView recycler = folder.requireView().findViewById(R.id.recycler);
        assertNotNull(recycler);
        assertEquals(count, recycler.getAdapter().getItemCount());
        if (count > 0) assertEquals(View.VISIBLE, recycler.getVisibility());
    }

    public static class EmptyPage extends LeanbackTypeFragment {
        @NonNull @Override public ViewModelProvider.Factory getDefaultViewModelProviderFactory() {
            return new ViewModelProvider.Factory() {
                @NonNull @Override public <T extends ViewModel> T create(@NonNull java.lang.Class<T> type) {
                    return type.cast(new SiteViewModel() {
                        @Override public void categoryContent(String key, String id, String page, boolean filter,
                                HashMap<String, String> extend) {
                            ((MutableLiveData<Result>) getResult()).setValue(Result.empty());
                        }
                    });
                }
            };
        }
    }
}
