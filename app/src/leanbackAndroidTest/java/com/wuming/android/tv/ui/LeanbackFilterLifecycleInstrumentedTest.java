package com.wuming.android.tv.ui;

import com.wuming.android.tv.ui.fragment.LeanbackFolderFragment;
import com.wuming.android.tv.ui.fragment.LeanbackTypeFragment;

import static org.junit.Assert.*;

import android.app.Activity;
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
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitor;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
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
        dismissKeepAliveHomeActivity();
        // Fixture-specific type ids keep these entries isolated in the process-global
        // FilterCache: real site configuration loads (async, from app startup or a
        // restored 49-site config) also key "movie"/"tv" filters and can otherwise
        // overwrite the fixture mid-test on cold instrumentation starts.
        for (String id : new String[]{"filter-fixture-movie", "filter-fixture-tv"}) {
            com.wuming.android.tv.bean.Class type = new Gson().fromJson(
                    "{\"type_id\":\"" + id + "\",\"filters\":[{\"key\":\"year\",\"value\":[{\"n\":\"All years\",\"v\":\"\"}]}]}",
                    com.wuming.android.tv.bean.Class.class);
            Result result = new Result();
            result.setTypes(List.of(type));
            seed(result);
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
                    // The child fragment reads FilterCache once in initView; that read can
                    // happen as soon as the parent reaches RESUMED, so reseed immediately
                    // before the transaction that moves it there.
                    seed(result);
                    activity.getSupportFragmentManager().beginTransaction()
                            .setMaxLifecycle(folder, Lifecycle.State.RESUMED).commitNow();
                    seed(result);
                    folder.getChildFragmentManager().executePendingTransactions();
                    assertRows(folder, 1, "first-visible");
                    folder.toggleFilter(true);
                    folder.onRefresh();
                    assertRows(folder, 1, "after-refresh");
                    folder.toggleFilter(false);
                    folder.toggleFilter(false);
                    assertRows(folder, 0, "closed");
                    folder.toggleFilter(true);
                    seed(result);
                    activity.getSupportFragmentManager().beginTransaction().detach(folder).commitNow();
                    activity.getSupportFragmentManager().beginTransaction().attach(folder).commitNow();
                    seed(result);
                    folder.getChildFragmentManager().executePendingTransactions();
                    assertRows(folder, 1, "recreated");
                });
            } finally { FilterCache.clear(); }
        }
    }

    private static void seed(Result result) {
        // Keep-alive launcher relaunches erase the fixture between seeds and reads.
        finishHomeActivityIfPresent();
        FilterCache.clear().put(result);
    }

    /**
     * Restrictive TV firmware relaunches the launcher activity (HomeActivity) from
     * its keep-alive path seconds after a force-stop or reinstall — repeatedly, not
     * once — and that activity's home load calls {@code FilterCache.clear().put(
     * realResult)} in this same process, erasing or overwriting the fixture. Finish
     * it whenever it appears. Its onDestroy removes the LiveData observers that own
     * the cache write, so a finished HomeActivity can no longer touch the cache.
     */
    private static void dismissKeepAliveHomeActivity() {
        long startedAt = android.os.SystemClock.elapsedRealtime();
        long overallDeadline = startedAt + 30000;
        long quietSince = startedAt;
        while (android.os.SystemClock.elapsedRealtime() < overallDeadline
                && android.os.SystemClock.elapsedRealtime() - quietSince < 8000) {
            if (finishHomeActivityIfPresent()) quietSince = android.os.SystemClock.elapsedRealtime();
            android.os.SystemClock.sleep(500);
        }
    }

    /** Finishes any live keep-alive HomeActivity; safe from any thread. */
    private static boolean finishHomeActivityIfPresent() {
        boolean[] finished = new boolean[1];
        Runnable finisher = () -> {
            ActivityLifecycleMonitor monitor = ActivityLifecycleMonitorRegistry.getInstance();
            java.util.LinkedHashSet<Activity> tracked = new java.util.LinkedHashSet<>();
            for (Stage stage : Stage.values()) tracked.addAll(monitor.getActivitiesInStage(stage));
            for (Activity activity : tracked) {
                if (activity instanceof com.wuming.android.tv.ui.activity.HomeActivity && !activity.isFinishing()) {
                    activity.finish();
                    finished[0] = true;
                }
            }
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) finisher.run();
        else InstrumentationRegistry.getInstrumentation().runOnMainSync(finisher);
        return finished[0];
    }

    private static void assertRows(LeanbackFolderFragment folder, int count, String step) {
        finishHomeActivityIfPresent();
        RecyclerView recycler = folder.requireView().findViewById(R.id.recycler);
        assertNotNull(recycler);
        RecyclerView.Adapter<?> adapter = recycler.getAdapter();
        androidx.leanback.widget.ObjectAdapter objectAdapter = adapter instanceof androidx.leanback.widget.ItemBridgeAdapter
                ? adapterItemAdapter(adapter) : null;
        StringBuilder describe = new StringBuilder("step=" + step
                + " shown=" + (adapter == null ? -1 : adapter.getItemCount())
                + " items=" + (objectAdapter == null ? -1 : objectAdapter.size())
                + " " + describeChild(folder) + ":");
        if (objectAdapter != null) {
            for (int i = 0; i < objectAdapter.size(); i++) {
                Object item = objectAdapter.get(i);
                describe.append(" [").append(i).append("]=").append(item == null ? "null"
                        : item.getClass().getSimpleName()
                        + (item instanceof androidx.leanback.widget.ListRow
                                ? "(inner=" + ((androidx.leanback.widget.ListRow) item).getAdapter().size() + ")" : ""));
            }
        }
        assertEquals(describe.toString(), count, adapter == null ? -1 : adapter.getItemCount());
        if (count > 0) assertEquals(View.VISIBLE, recycler.getVisibility());
    }

    private static androidx.leanback.widget.ObjectAdapter adapterItemAdapter(RecyclerView.Adapter<?> adapter) {
        for (java.lang.reflect.Field field : adapter.getClass().getDeclaredFields()) {
            if (androidx.leanback.widget.ObjectAdapter.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                try { return (androidx.leanback.widget.ObjectAdapter) field.get(adapter); } catch (IllegalAccessException ignored) { }
            }
        }
        return null;
    }

    private static String describeChild(LeanbackFolderFragment folder) {
        Fragment child = folder.getChildFragmentManager().findFragmentById(R.id.container);
        if (child == null) return "child=null";
        try {
            java.lang.reflect.Field visible = child.getClass().getDeclaredField("filterVisible");
            visible.setAccessible(true);
            java.lang.reflect.Field filters = child.getClass().getDeclaredField("mFilters");
            filters.setAccessible(true);
            Object list = filters.get(child);
            return "child=" + child.getClass().getSimpleName()
                    + " filterVisible=" + visible.get(child)
                    + " mFilters=" + (list == null ? "null" : ((java.util.List<?>) list).size())
                    + " typeFilterFlag=" + folder.getType().getFilter();
        } catch (ReflectiveOperationException error) {
            return "child-introspection-failed: " + error;
        }
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
