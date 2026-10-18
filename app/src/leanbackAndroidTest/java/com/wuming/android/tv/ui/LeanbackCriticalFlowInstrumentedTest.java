package com.wuming.android.tv.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.ListRow;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.wuming.android.tv.R;
import com.wuming.android.tv.bean.Class;
import com.wuming.android.tv.bean.Flag;
import com.wuming.android.tv.bean.Result;
import com.wuming.android.tv.bean.Vod;
import com.wuming.android.tv.ui.activity.LeanbackKeepActivity;
import com.wuming.android.tv.ui.activity.LeanbackFastSearchActivity;
import com.wuming.android.tv.ui.activity.SettingActivity;
import com.wuming.android.tv.ui.activity.VodActivity;
import com.wuming.android.tv.ui.fragment.LeanbackFolderFragment;
import com.wuming.android.tv.ui.fragment.LeanbackTypeFragment;
import com.wuming.android.tv.ui.fragment.TypeFragmentTestHarness;
import com.wuming.android.tv.utils.LayoutConfig;
import com.wuming.android.tv.setting.AppSettings;
import com.wuming.android.tv.ui.adapter.FlagAdapter;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.HashMap;

@RunWith(AndroidJUnit4.class)
public class LeanbackCriticalFlowInstrumentedTest {

    @Test
    public void activePlaybackSourceKeepsASelectedVisualState() {
        Context context = ApplicationProvider.getApplicationContext();
        Flag first = Flag.create("Source one");
        Flag second = Flag.create("Source two");
        FlagAdapter adapter = new FlagAdapter(item -> { });
        adapter.add(first);
        adapter.add(second);
        adapter.setActivated(second);
        FrameLayout parent = new FrameLayout(context);

        FlagAdapter.ViewHolder firstHolder = adapter.onCreateViewHolder(parent, 0);
        FlagAdapter.ViewHolder secondHolder = adapter.onCreateViewHolder(parent, 0);
        adapter.onBindViewHolder(firstHolder, 0);
        adapter.onBindViewHolder(secondHolder, 1);

        assertTrue(secondHolder.itemView.isActivated());
        assertTrue(secondHolder.itemView.isSelected());
        assertTrue(secondHolder.itemView.getBackground().isStateful());
        assertTrue(!firstHolder.itemView.isSelected());
    }

    @Test
    public void settingsCloudShareRouteAcceptsRemoteFocusAndToggles() {
        int original = AppSettings.getLinkRoute();
        AppSettings.putLinkRoute(AppSettings.LINK_ROUTE_BUILTIN);
        Context context = ApplicationProvider.getApplicationContext();
        Intent intent = new Intent(context, SettingActivity.class);

        try (ActivityScenario<SettingActivity> scenario = TestActivityLauncher.launch(intent)) {
            scenario.onActivity(activity -> {
                View route = activity.findViewById(R.id.linkRoute);
                View cloudStorage = activity.findViewById(R.id.cloudStorage);
                View cache = activity.findViewById(R.id.cache);
                TextView routeText = activity.findViewById(R.id.linkRouteText);
                assertNotNull(route);
                assertNotNull(cloudStorage);
                assertNotNull(cache);
                assertNotNull(routeText);
                // The DLNA pairing row sits between cloud storage and link route in the
                // current settings layout; walk the chain through it. The Bilibili login
                // entry now lives on the Settings > Cloud Storage page.
                View dlnaPairing = activity.findViewById(R.id.dlnaPairing);
                assertNotNull(dlnaPairing);
                assertTrue(route.isFocusable());
                assertEquals(dlnaPairing, cloudStorage.focusSearch(View.FOCUS_DOWN));
                assertEquals(route, dlnaPairing.focusSearch(View.FOCUS_DOWN));
                assertEquals(route, cache.focusSearch(View.FOCUS_UP));
                assertTrue(route.requestFocus());
                assertTrue(route.hasFocus());
                assertEquals(activity.getString(R.string.link_route_builtin),
                        routeText.getText().toString());
                assertTrue(route.performClick());
                assertEquals(activity.getString(R.string.link_route_direct),
                        routeText.getText().toString());
            });
        } finally {
            AppSettings.putLinkRoute(original);
        }
    }

    @Test
    public void settingsRouteRespondsToInjectedDpadAndOk() {
        android.app.Instrumentation input = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation();
        input.setInTouchMode(false);
        int original = AppSettings.getLinkRoute();
        AppSettings.putLinkRoute(AppSettings.LINK_ROUTE_BUILTIN);
        try (ActivityScenario<SettingActivity> scenario = TestActivityLauncher.launch(SettingActivity.class)) {
            scenario.onActivity(activity -> assertTrue(activity.findViewById(R.id.cache).requestFocus()));
            input.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
            input.waitForIdleSync();
            scenario.onActivity(activity -> {
                View route = activity.findViewById(R.id.linkRoute);
                assertTrue("D-pad must reach route from cache", route.hasFocus());
                assertTrue("Focus must be visible", route.isShown());
                assertTrue("Focus must have a stateful outline", route.getBackground() != null
                        && route.getBackground().isStateful());
            });
            input.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
            input.waitForIdleSync();
            assertEquals(AppSettings.LINK_ROUTE_DIRECT, AppSettings.getLinkRoute());
            input.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
            input.waitForIdleSync();
            scenario.onActivity(activity -> assertTrue(activity.findViewById(R.id.cache).hasFocus()));
        } finally {
            AppSettings.putLinkRoute(original);
        }
    }

    @Test
    public void nestedSearchResultsAlwaysUseMultiColumnTvGrid() {
        Context context = ApplicationProvider.getApplicationContext();
        Intent intent = new Intent(context, LeanbackFastSearchActivity.class).putExtra("title", "");

        try (ActivityScenario<LeanbackFastSearchActivity> scenario = TestActivityLauncher.launch(intent)) {
            assertSearchGrids(scenario);
            scenario.recreate();
            assertSearchGrids(scenario);
        }
    }

    @Test
    public void vodCategoryScreenShowsFocusableCategoryNavigation() {
        Context context = ApplicationProvider.getApplicationContext();
        Class type = new Class();
        type.setTypeId("hot-tv");
        type.setTypeName("Hot TV");
        Result result = Result.empty();
        result.setTypes(List.of(type));
        Intent intent = new Intent(context, VodActivity.class)
                .putExtra("key", "test-site")
                .putExtra("result", result);

        try (ActivityScenario<VodActivity> scenario = TestActivityLauncher.launch(intent)) {
            assertCategoryFocusBoundary(scenario);
            scenario.recreate();
            assertCategoryFocusBoundary(scenario);
        }
    }

    @Test
    public void clickingVodCategoryStartsThumbnailRefresh() {
        Context context = ApplicationProvider.getApplicationContext();
        Class type = new Class();
        type.setTypeId("hot-tv");
        type.setTypeName("Hot TV");
        Result result = Result.empty();
        result.setTypes(List.of(type));
        Intent intent = new Intent(context, VodActivity.class)
                .putExtra("key", "test-site")
                .putExtra("result", result);

        try (ActivityScenario<VodActivity> scenario = TestActivityLauncher.launch(intent)) {
            scenario.onActivity(activity -> {
                activity.getSupportFragmentManager().executePendingTransactions();
                LeanbackFolderFragment folder = findFolder(activity);
                folder.getChildFragmentManager().executePendingTransactions();
                LeanbackTypeFragment fragment = folder.getChildFragmentManager().getFragments().stream()
                        .filter(LeanbackTypeFragment.class::isInstance)
                        .map(LeanbackTypeFragment.class::cast)
                        .findFirst()
                        .orElseThrow();
                injectListResult(fragment, 1);

                SwipeRefreshLayout refresh = fragment.requireView().findViewById(R.id.swipeLayout);
                refresh.setRefreshing(false);
                HorizontalGridView categories = activity.findViewById(R.id.recycler);
                RecyclerView.ViewHolder category = categories.findViewHolderForAdapterPosition(0);
                assertNotNull(category);

                category.itemView.performClick();

                assertTrue(refresh.isRefreshing());
            });
        }
    }

    @Test
    public void hotTvFolderNavigationCreatesANestedGridScreen() {
        Context context = ApplicationProvider.getApplicationContext();
        Class type = new Class();
        type.setTypeId("hot-tv");
        type.setTypeName("Hot TV");
        Result result = Result.empty();
        result.setTypes(List.of(type));
        Intent intent = new Intent(context, VodActivity.class)
                .putExtra("key", "test-site")
                .putExtra("result", result);

        try (ActivityScenario<VodActivity> scenario = TestActivityLauncher.launch(intent)) {
            scenario.onActivity(activity -> {
                activity.getSupportFragmentManager().executePendingTransactions();
                LeanbackFolderFragment folder = activity.getSupportFragmentManager().getFragments().stream()
                        .filter(LeanbackFolderFragment.class::isInstance)
                        .map(LeanbackFolderFragment.class::cast)
                        .findFirst()
                        .orElseThrow();

                folder.openFolder("ju-ju", new HashMap<>());
                folder.getChildFragmentManager().executePendingTransactions();

                Fragment nested = folder.getChildFragmentManager().getFragments().stream()
                        .filter(LeanbackTypeFragment.class::isInstance)
                        .filter(fragment -> fragment.requireArguments().getBoolean("nested"))
                        .findFirst()
                        .orElseThrow();
                assertTrue(nested.isAdded());
                assertEquals(1, folder.getChildFragmentManager().getBackStackEntryCount());
            });
        }
    }

    @Test
    public void nestedFolderRendersProviderListResultsAsFocusableGridRowsAfterRecreation() {
        Context context = ApplicationProvider.getApplicationContext();
        Class type = new Class();
        type.setTypeId("hot-tv");
        type.setTypeName("Hot TV");
        Result initial = Result.empty();
        initial.setTypes(List.of(type));
        Intent intent = new Intent(context, VodActivity.class)
                .putExtra("key", "test-site")
                .putExtra("result", initial);

        try (ActivityScenario<VodActivity> scenario = TestActivityLauncher.launch(intent)) {
            openNestedFolderAndInjectListResults(scenario);
            assertNestedRowsAndFocus(scenario);
            scenario.recreate();
            openNestedFolderAndInjectListResults(scenario);
            assertNestedRowsAndFocus(scenario);
        }
    }

    @Test
    public void keepActivityIsDeclaredAndEnabled() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        PackageManager packageManager = context.getPackageManager();
        ActivityInfo info = packageManager.getActivityInfo(
                new ComponentName(context, LeanbackKeepActivity.class), 0);
        assertNotNull(info);
        assertTrue(info.enabled);
    }

    private static void assertCategoryFocusBoundary(ActivityScenario<VodActivity> scenario) {
        scenario.onActivity(activity -> {
            HorizontalGridView categories = activity.findViewById(R.id.recycler);
            assertNotNull(categories);
            assertNotNull(categories.getAdapter());
            assertEquals(1, categories.getAdapter().getItemCount());
            assertTrue(categories.isFocusable());
            categories.requestFocus();
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT));
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT));
            View focused = activity.getCurrentFocus();
            assertNotNull(focused);
            assertTrue(focused == categories || isDescendant(categories, focused));
        });
    }

    private static boolean isDescendant(View parent, View child) {
        android.view.ViewParent current = child.getParent();
        while (current != null) {
            if (current == parent) return true;
            current = current.getParent();
        }
        return false;
    }

    private static void assertSearchGrids(ActivityScenario<LeanbackFastSearchActivity> scenario) {
        scenario.onActivity(activity -> {
            assertGrid(activity.findViewById(R.id.mGridView));
            assertGrid(activity.findViewById(R.id.mGridViewFilter));
            HorizontalGridView sources = activity.findViewById(R.id.mGridViewWord);
            assertNotNull(sources);
            assertTrue(sources.isFocusable());
        });
    }

    private static void assertGrid(RecyclerView recycler) {
        assertNotNull(recycler);
        assertTrue(recycler.getLayoutManager() instanceof GridLayoutManager);
        GridLayoutManager manager = (GridLayoutManager) recycler.getLayoutManager();
        assertEquals(LayoutConfig.SEARCH_GRID_SPAN_COUNT_LEANBACK, manager.getSpanCount());
        assertTrue(manager.getSpanCount() > 1);
    }

    private static void openNestedFolderAndInjectListResults(ActivityScenario<VodActivity> scenario) {
        scenario.onActivity(activity -> {
            activity.getSupportFragmentManager().executePendingTransactions();
            LeanbackFolderFragment folder = findFolder(activity);
            FragmentManager children = folder.getChildFragmentManager();
            LeanbackTypeFragment nested = findNestedOrNull(folder);
            if (nested == null) {
                if (children.getBackStackEntryCount() > 0) {
                    children.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
                }
                folder.openFolder("ju-ju", new HashMap<>());
                children.executePendingTransactions();
                nested = findNested(folder);
            }
            injectListResult(nested, 7);
        });
    }

    private static void assertNestedRowsAndFocus(ActivityScenario<VodActivity> scenario) {
        scenario.onActivity(activity -> {
            LeanbackFolderFragment folder = findFolder(activity);
            assertEquals(1, folder.getChildFragmentManager().getBackStackEntryCount());
            LeanbackTypeFragment nested = findNested(folder);
            ArrayObjectAdapter adapter = getObjectAdapter(nested);
            assertTrue(adapter.size() >= 2);
            assertTrue(adapter.get(0) instanceof ListRow);
            ListRow first = (ListRow) adapter.get(0);
            assertTrue(first.getAdapter().size() > 1);

            RecyclerView recycler = nested.requireView().findViewById(R.id.recycler);
            assertNotNull(recycler);
            assertTrue(recycler.isFocusable());
            recycler.requestFocus();
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT));
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT));
            assertNotNull(activity.getCurrentFocus());
            assertTrue(activity.getCurrentFocus() == recycler || isDescendant(recycler, activity.getCurrentFocus()));
        });
    }

    private static LeanbackFolderFragment findFolder(VodActivity activity) {
        return activity.getSupportFragmentManager().getFragments().stream()
                .filter(LeanbackFolderFragment.class::isInstance)
                .map(LeanbackFolderFragment.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private static LeanbackTypeFragment findNested(LeanbackFolderFragment folder) {
        LeanbackTypeFragment nested = findNestedOrNull(folder);
        if (nested == null) throw new AssertionError("Nested folder fragment was not added");
        return nested;
    }

    private static LeanbackTypeFragment findNestedOrNull(LeanbackFolderFragment folder) {
        folder.getChildFragmentManager().executePendingTransactions();
        return folder.getChildFragmentManager().getFragments().stream()
                .filter(LeanbackTypeFragment.class::isInstance)
                .map(LeanbackTypeFragment.class::cast)
                .filter(fragment -> fragment.requireArguments().getBoolean("nested"))
                .findFirst()
                .orElse(null);
    }

    private static void injectListResult(LeanbackTypeFragment fragment, int count) {
        try {
            Result result = Result.empty();
            java.util.ArrayList<Vod> items = new java.util.ArrayList<>();
            for (int index = 0; index < count; index++) {
                Vod vod = new Vod();
                vod.setId("fixture-" + index);
                vod.setName("Fixture " + index);
                items.add(vod);
            }
            result.setList(items);
            TypeFragmentTestHarness.deliver(fragment, result);
        } catch (RuntimeException error) {
            throw new AssertionError(error);
        }
    }

    private static ArrayObjectAdapter getObjectAdapter(LeanbackTypeFragment fragment) {
        return TypeFragmentTestHarness.adapter(fragment);
    }
}
