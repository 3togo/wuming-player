package com.wuming.android.tv.ui;
import com.wuming.android.tv.setting.DisplaySettings;
import com.wuming.android.tv.setting.PlayerSettings;
import com.wuming.android.tv.setting.AppSettings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.wuming.android.tv.MobileProduct;
import com.wuming.android.tv.R;
import com.wuming.android.tv.ui.activity.HistoryActivity;
import com.wuming.android.tv.ui.activity.HomeActivity;
import com.wuming.android.tv.ui.activity.MobileFastSearchActivity;
import com.wuming.android.tv.ui.activity.MobileKeepActivity;
import com.wuming.android.tv.ui.activity.MobileSearchActivity;
import com.wuming.android.tv.ui.activity.VideoActivity;
import com.wuming.android.tv.ui.fragment.MobileCollectFragment;
import com.wuming.android.tv.ui.fragment.SettingPlayerFragment;
import com.wuming.android.tv.ui.fragment.VodFragment;
import com.wuming.android.tv.utils.LayoutConfig;
import com.google.android.material.navigation.NavigationBarView;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class MobileCriticalFlowInstrumentedTest {

    @Test
    public void frontPageVodNavigationSurvivesActivityRecreation() {
        try (ActivityScenario<HomeActivity> scenario = TestActivityLauncher.launch(HomeActivity.class)) {
            selectVodAndAssertContent(scenario);

            scenario.recreate();

            selectVodAndAssertContent(scenario);
        }
    }

    @Test
    public void navigationAndLoadingRemainStableAcrossRepeatedOrientationChanges() {
        try (ActivityScenario<HomeActivity> scenario = TestActivityLauncher.launch(HomeActivity.class)) {
            selectVodAndAssertContent(scenario);
            waitForHomeContentToSettle(scenario);
            for (int attempt = 0; attempt < 3; attempt++) {
                requestHomeOrientationAndAssertVod(scenario,
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                        Configuration.ORIENTATION_PORTRAIT);
                requestHomeOrientationAndAssertVod(scenario,
                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                        Configuration.ORIENTATION_LANDSCAPE);
            }
        }
    }

    private static void waitForHomeContentToSettle(ActivityScenario<HomeActivity> scenario) {
        for (int attempt = 0; attempt < 100; attempt++) {
            boolean[] settled = {false};
            scenario.onActivity(activity -> {
                RecyclerView types = activity.findViewById(R.id.type);
                View progress = activity.findViewById(R.id.progress);
                settled[0] = types != null && types.getAdapter() != null
                        && types.getAdapter().getItemCount() > 0
                        && progress != null && progress.getVisibility() != View.VISIBLE;
            });
            if (settled[0]) return;
            SystemClock.sleep(100);
        }
    }

    @Test
    public void historyScreenRedrawsInLandscapeAndPortrait() {
        try (ActivityScenario<HistoryActivity> scenario = TestActivityLauncher.launch(HistoryActivity.class)) {
            LayoutSnapshot landscape = requestOrientationAndAssertContent(scenario, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                    Configuration.ORIENTATION_LANDSCAPE);
            LayoutSnapshot portrait = requestOrientationAndAssertContent(scenario, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                    Configuration.ORIENTATION_PORTRAIT);

            assertTrue(landscape.screenWidth > portrait.screenWidth);
            assertTrue(landscape.itemWidth > portrait.itemWidth);
            assertNotEquals(landscape.spanCount, portrait.spanCount);
            assertTrue(landscape.fontSize > 0 && landscape.fontSize < landscape.itemWidth / 4);
            assertTrue(portrait.fontSize > 0 && portrait.fontSize < portrait.itemWidth / 4);
        }
    }

    @Test
    public void videoLaunchContractCarriesRequiredDetailExtras() {
        RecordingActivity[] holder = new RecordingActivity[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            holder[0] = new RecordingActivity();
            VideoActivity.start(holder[0], "site-key", "vod-id", "Movie", "poster");
        });

        RecordingActivity activity = holder[0];
        assertNotNull(activity);
        Intent intent = activity.startedIntent;
        assertNotNull(intent);
        assertNotNull(intent.getComponent());
        assertEquals(VideoActivity.class.getName(), intent.getComponent().getClassName());
        assertEquals("site-key", intent.getStringExtra("key"));
        assertEquals("vod-id", intent.getStringExtra("id"));
        assertEquals("Movie", intent.getStringExtra("name"));
        assertEquals("poster", intent.getStringExtra("pic"));
        assertFalse(intent.getBooleanExtra("collect", true));
    }

    @Test
    public void homeAndSearchLayoutsRedrawAcrossBothOrientations() {
        try (ActivityScenario<HomeActivity> home = TestActivityLauncher.launch(HomeActivity.class)) {
            selectVodAndAssertContent(home);
            requestHomeOrientationAndAssertVod(home, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                    Configuration.ORIENTATION_LANDSCAPE);
            requestHomeOrientationAndAssertVod(home, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                    Configuration.ORIENTATION_PORTRAIT);
        }

        int originalSiteLayout = DisplaySettings.getSiteListLayout();
        int originalSearchView = DisplaySettings.getSearchView();
        DisplaySettings.putSiteListLayout(0);
        DisplaySettings.putSearchView(2);
        try {
            Context context = ApplicationProvider.getApplicationContext();
            Intent intent = new Intent(context, MobileFastSearchActivity.class).putExtra("title", "");
            try (ActivityScenario<MobileFastSearchActivity> search = TestActivityLauncher.launch(intent)) {
                requestSearchOrientationAndAssertGrid(search, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                        Configuration.ORIENTATION_LANDSCAPE,
                        LayoutConfig.VIDEO_GRID_SPAN_COUNT_MOBILE_LANDSCAPE);
                requestSearchOrientationAndAssertGrid(search, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                        Configuration.ORIENTATION_PORTRAIT,
                        LayoutConfig.VIDEO_GRID_SPAN_COUNT_MOBILE_PORTRAIT);
                search.recreate();
                requestSearchOrientationAndAssertGrid(search, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                        Configuration.ORIENTATION_PORTRAIT,
                        LayoutConfig.VIDEO_GRID_SPAN_COUNT_MOBILE_PORTRAIT);
            }
        } finally {
            DisplaySettings.putSiteListLayout(originalSiteLayout);
            DisplaySettings.putSearchView(originalSearchView);
        }
    }

    @Test
    public void playerSettingsAndSelectedFragmentSurviveRecreation() {
        PlayerSettingsSnapshot original = PlayerSettingsSnapshot.capture();
        try {
            try (ActivityScenario<HomeActivity> scenario = TestActivityLauncher.launch(HomeActivity.class)) {
                configurePlayerThroughVisibleFragment(scenario);
                showPlayerSettingsAndAssertValues(scenario);
                scenario.recreate();
                showPlayerSettingsAndAssertValues(scenario);
            }
        } finally {
            original.restore();
        }
    }

    @Test
    public void playerSettingsSurviveColdActivityRelaunch() {
        PlayerSettingsSnapshot original = PlayerSettingsSnapshot.capture();
        try {
            try (ActivityScenario<HomeActivity> first = TestActivityLauncher.launch(HomeActivity.class)) {
                configurePlayerThroughVisibleFragment(first);
                showPlayerSettingsAndAssertValues(first);
            }
            try (ActivityScenario<HomeActivity> relaunched = TestActivityLauncher.launch(HomeActivity.class)) {
                showPlayerSettingsAndAssertValues(relaunched);
            }
        } finally {
            original.restore();
        }
    }

    private static void configurePlayerThroughVisibleFragment(ActivityScenario<HomeActivity> scenario) {
        scenario.onActivity(activity -> configurePlayerSettings());
        showPlayerSettings(scenario);
        scenario.onActivity(activity -> {
            SettingPlayerFragment fragment = (SettingPlayerFragment) activity
                    .getSupportFragmentManager().getPrimaryNavigationFragment();
            assertNotNull(fragment);
            fragment.setUa("TVBox-Test-UA");
            fragment.setBuffer(7);
            fragment.setSpeed(4.5f);
        });
    }

    private static void configurePlayerSettings() {
        PlayerSettings.putRender(1);
        DisplaySettings.putScale(1);
        PlayerSettings.putBackground(1);
        PlayerSettings.putCaption(true);
        PlayerSettings.putTunnel(false);
        PlayerSettings.putAudioPrefer(true);
        PlayerSettings.putVideoPrefer(true);
        PlayerSettings.putPreferAAC(true);
        PlayerSettings.putDanmakuLoad(true);
        AppSettings.putAdblock(false);
    }

    @Test
    public void collectionGridRedrawsAndRestoresAcrossOrientations() {
        int originalSiteLayout = DisplaySettings.getSiteListLayout();
        DisplaySettings.putSiteListLayout(0);
        Context context = ApplicationProvider.getApplicationContext();
        Intent intent = new Intent(context, MobileSearchActivity.class).putExtra("keyword", "");
        try (ActivityScenario<MobileSearchActivity> scenario = TestActivityLauncher.launch(intent)) {
            scenario.onActivity(activity -> activity.getSupportFragmentManager().beginTransaction()
                    .replace(R.id.container, MobileCollectFragment.newInstance("Fixture"), "collect-test")
                    .commitNow());
            requestCollectionOrientationAndAssertGrid(scenario, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                    Configuration.ORIENTATION_LANDSCAPE,
                    LayoutConfig.VIDEO_GRID_SPAN_COUNT_MOBILE_LANDSCAPE);
            requestCollectionOrientationAndAssertGrid(scenario, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                    Configuration.ORIENTATION_PORTRAIT,
                    LayoutConfig.VIDEO_GRID_SPAN_COUNT_MOBILE_PORTRAIT);
            scenario.recreate();
            requestCollectionOrientationAndAssertGrid(scenario, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                    Configuration.ORIENTATION_PORTRAIT,
                    LayoutConfig.VIDEO_GRID_SPAN_COUNT_MOBILE_PORTRAIT);
        } finally {
            DisplaySettings.putSiteListLayout(originalSiteLayout);
        }
    }

    @Test
    public void playerSettingControlsHaveTouchTargetsAndVisibleLabels() {
        try (ActivityScenario<HomeActivity> scenario = TestActivityLauncher.launch(HomeActivity.class)) {
            showPlayerSettings(scenario);
            scenario.onActivity(activity -> {
                int minimum = Math.round(44 * activity.getResources().getDisplayMetrics().density);
                int[] controls = {R.id.render, R.id.scale, R.id.caption, R.id.buffer, R.id.speed,
                        R.id.tunnel, R.id.audioDecode, R.id.videoDecode, R.id.aac,
                        R.id.danmakuLoad, R.id.adblock, R.id.background, R.id.ua};
                for (int id : controls) {
                    View control = activity.findViewById(id);
                    assertNotNull(control);
                    assertTrue("inactive control: " + activity.getResources().getResourceEntryName(id), control.isClickable());
                    assertTrue("undersized touch target: " + id, control.getHeight() >= minimum);
                    assertTrue("missing visible label: " + id, hasVisibleText(control));
                }
            });
        }
    }

    @Test
    public void playerSettingsLayoutScalesAcrossFontAndDisplaySizes() {
        Context base = ApplicationProvider.getApplicationContext();
        View normal = inflatePlayerSettings(base, 1.0f);
        View largeFont = inflatePlayerSettings(base, 1.8f);

        measureAtWidth(normal, 720);
        measureAtWidth(largeFont, 720);
        TextView normalText = normal.findViewById(R.id.renderText);
        TextView largeText = largeFont.findViewById(R.id.renderText);
        assertTrue(largeText.getTextSize() > normalText.getTextSize());
        assertEquals(normal.findViewById(R.id.render).getWidth(), largeFont.findViewById(R.id.render).getWidth());
        assertTrue(largeFont.findViewById(R.id.render).getHeight() >= normal.findViewById(R.id.render).getHeight());

        int previousWidth = 0;
        for (int width : new int[]{480, 720, 1080, 1440}) {
            View layout = inflatePlayerSettings(base, 1.0f);
            measureAtWidth(layout, width);
            int rowWidth = layout.findViewById(R.id.render).getWidth();
            assertTrue(rowWidth > previousWidth);
            assertTrue(rowWidth <= width);
            previousWidth = rowWidth;
        }
    }

    private static View inflatePlayerSettings(Context base, float fontScale) {
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.fontScale = fontScale;
        Context scaled = base.createConfigurationContext(configuration);
        Context themed = new ContextThemeWrapper(scaled, R.style.Theme_App);
        return LayoutInflater.from(themed).inflate(R.layout.fragment_setting_player, null, false);
    }

    private static void measureAtWidth(View view, int width) {
        int widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(10_000, View.MeasureSpec.AT_MOST);
        view.measure(widthSpec, heightSpec);
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    }

    private static void selectVodAndAssertContent(ActivityScenario<HomeActivity> scenario) {
        scenario.onActivity(activity -> {
            assertNotNull(activity.findViewById(R.id.navigation));
            activity.change(0);
            activity.getSupportFragmentManager().executePendingTransactions();
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        scenario.onActivity(MobileCriticalFlowInstrumentedTest::assertHomeVodContent);
    }

    private static void requestHomeOrientationAndAssertVod(ActivityScenario<HomeActivity> scenario,
                                                            int requested, int expected) {
        scenario.onActivity(activity -> activity.setRequestedOrientation(requested));
        waitForHomeOrientation(scenario, expected);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        scenario.onActivity(MobileCriticalFlowInstrumentedTest::assertHomeVodContent);
    }

    private static void assertHomeVodContent(HomeActivity activity) {
        View container = activity.findViewById(R.id.container);
        assertNotNull(container);
        assertTrue(container.getWidth() > 0);
        assertTrue(container.getHeight() > 0);
        assertTrue(container instanceof ViewGroup);
        assertTrue(((ViewGroup) container).getChildCount() > 0);

        androidx.fragment.app.Fragment fragment =
                activity.getSupportFragmentManager().getPrimaryNavigationFragment();
        assertTrue(fragment instanceof VodFragment);
        assertFalse(fragment.isDetached());
        assertNotNull(fragment.getView());
        assertTrue(fragment.getView().isAttachedToWindow());
        assertEquals(R.id.vod,
                ((NavigationBarView) activity.findViewById(R.id.navigation)).getSelectedItemId());

        if (activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            View navigation = activity.findViewById(R.id.navigation);
            assertNotNull(navigation);
            assertEquals(LayoutConfig.getMobileNavigationRailWidthPixels(activity), navigation.getWidth());
            assertTrue(navigation.getWidth() < container.getWidth());

            View vod = activity.findViewById(R.id.vod);
            View live = activity.findViewById(R.id.live);
            View setting = activity.findViewById(R.id.setting);
            assertNotNull(vod);
            assertNotNull(live);
            assertNotNull(setting);
            assertEquals(vod.getHeight(), live.getHeight());
            assertEquals(live.getHeight(), setting.getHeight());
            int navigationTextSize = LayoutConfig.getMobileNavigationTextSizePixels(activity);
            assertDescendantTextSize(vod, navigationTextSize);
            assertDescendantTextSize(live, navigationTextSize);
            assertDescendantTextSize(setting, navigationTextSize);
            int[] navigationLocation = new int[2];
            int[] vodLocation = new int[2];
            int[] settingLocation = new int[2];
            navigation.getLocationOnScreen(navigationLocation);
            vod.getLocationOnScreen(vodLocation);
            setting.getLocationOnScreen(settingLocation);
            assertTrue(vodLocation[1] >= navigationLocation[1]);
            assertTrue(settingLocation[1] + setting.getHeight()
                    <= navigationLocation[1] + navigation.getHeight());

            View link = activity.findViewById(R.id.link);
            assertNotNull(link);
            assertEquals(LayoutConfig.getMobileHomeFabSizePixels(activity), link.getWidth());
            assertEquals(link.getWidth(), link.getHeight());

            RecyclerView types = activity.findViewById(R.id.type);
            View progress = activity.findViewById(R.id.progress);
            assertNotNull(types);
            assertNotNull(progress);
            if (types.getAdapter() != null && types.getAdapter().getItemCount() > 0) {
                assertEquals(View.GONE, progress.getVisibility());
            }
        } else {
            TextView title = activity.findViewById(R.id.title);
            View link = activity.findViewById(R.id.link);
            assertNotNull(title);
            assertNotNull(link);
            assertTrue(title.getWidth()
                    <= LayoutConfig.getMobilePortraitTitleMaxWidthPixels(activity));
            assertEquals(LayoutConfig.getMobilePortraitTitleTextSizePixels(activity),
                    Math.round(title.getTextSize()));
            assertEquals(LayoutConfig.getMobilePortraitFabSizePixels(activity), link.getWidth());
            assertEquals(link.getWidth(), link.getHeight());
        }
    }

    private static void assertDescendantTextSize(View view, int expected) {
        if (view instanceof TextView text) {
            assertEquals(expected, Math.round(text.getTextSize()));
        } else if (view instanceof ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                assertDescendantTextSize(group.getChildAt(index), expected);
            }
        }
    }

    private static void requestSearchOrientationAndAssertGrid(ActivityScenario<MobileFastSearchActivity> scenario,
                                                               int requested, int expected, int spanCount) {
        scenario.onActivity(activity -> activity.setRequestedOrientation(requested));
        waitForSearchOrientation(scenario, expected);
        scenario.onActivity(activity -> {
            TextView title = activity.findViewById(R.id.mSearchTitle);
            assertNotNull(title);
            assertEquals("", activity.getIntent().getStringExtra("title"));
            RecyclerView results = activity.findViewById(R.id.mGridView);
            RecyclerView filtered = activity.findViewById(R.id.mGridViewFilter);
            assertGridSpan("results", results, spanCount);
            assertGridSpan("filtered", filtered, spanCount);
            assertEquals(LayoutConfig.getSearchGridSpanCount(activity), spanCount);
        });
    }

    private static void requestCollectionOrientationAndAssertGrid(ActivityScenario<MobileSearchActivity> scenario,
                                                                   int requested, int expected, int spanCount) {
        scenario.onActivity(activity -> activity.setRequestedOrientation(requested));
        waitForSearchActivityOrientation(scenario, expected);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        scenario.onActivity(activity -> {
            assertNotNull(activity.getSupportFragmentManager().findFragmentByTag("collect-test"));
            RecyclerView recycler = activity.findViewById(R.id.recycler);
            assertGridSpan(recycler, spanCount);
            View column = activity.findViewById(R.id.llCollect);
            assertNotNull(column);
            assertEquals(LayoutConfig.getSiteColumnWidthPixels(activity), column.getWidth());
        });
    }

    private static void showPlayerSettings(ActivityScenario<HomeActivity> scenario) {
        scenario.onActivity(activity -> {
            activity.change(2);
            activity.getSupportFragmentManager().executePendingTransactions();
        });
        // Fragment OPEN transitions can defer RESUMED/initEvent beyond waitForIdleSync.
        // Wait for the real lifecycle/layout boundary before inspecting or using controls.
        for (int attempt = 0; attempt < 100; attempt++) {
            boolean[] ready = {false};
            scenario.onActivity(activity -> {
                androidx.fragment.app.Fragment fragment = activity.getSupportFragmentManager()
                        .getPrimaryNavigationFragment();
                ready[0] = fragment instanceof SettingPlayerFragment && fragment.isResumed()
                        && fragment.getView() != null && fragment.getView().isLaidOut();
            });
            if (ready[0]) return;
            SystemClock.sleep(50);
        }
        org.junit.Assert.fail("Player settings did not resume and lay out within 5 seconds");
    }

    private static void showPlayerSettingsAndAssertValues(ActivityScenario<HomeActivity> scenario) {
        showPlayerSettings(scenario);
        scenario.onActivity(activity -> {
            assertTrue(activity.getSupportFragmentManager().getFragments().stream()
                    .anyMatch(fragment -> fragment instanceof SettingPlayerFragment && fragment.isVisible()));
            assertEquals("TVBox-Test-UA", PlayerSettings.getUa());
            assertEquals("TVBox-Test-UA", ((TextView) activity.findViewById(R.id.uaText)).getText().toString());
            assertEquals("7", ((TextView) activity.findViewById(R.id.bufferText)).getText().toString());
            assertEquals("4.5", ((TextView) activity.findViewById(R.id.speedText)).getText().toString());
            assertTrue(PlayerSettings.isCaption());
            assertTrue(PlayerSettings.isAudioPrefer());
            assertTrue(PlayerSettings.isVideoPrefer());
            assertTrue(PlayerSettings.isPreferAAC());
            assertTrue(PlayerSettings.isDanmakuLoad());
            assertFalse(AppSettings.isAdblock());
        });
    }

    private static void assertGridSpan(RecyclerView recycler, int expected) {
        assertGridSpan("grid", recycler, expected);
    }

    private static void assertGridSpan(String name, RecyclerView recycler, int expected) {
        assertNotNull(recycler);
        assertTrue(name + " expected GridLayoutManager but was "
                        + (recycler.getLayoutManager() == null
                        ? "null" : recycler.getLayoutManager().getClass().getName()),
                recycler.getLayoutManager() instanceof GridLayoutManager);
        assertEquals(expected, ((GridLayoutManager) recycler.getLayoutManager()).getSpanCount());
    }

    private static boolean hasVisibleText(View view) {
        if (view instanceof TextView text && text.getVisibility() == View.VISIBLE && text.length() > 0) return true;
        if (!(view instanceof ViewGroup group)) return false;
        for (int index = 0; index < group.getChildCount(); index++) {
            if (hasVisibleText(group.getChildAt(index))) return true;
        }
        return false;
    }

    private static void waitForHomeOrientation(ActivityScenario<HomeActivity> scenario, int expected) {
        for (int attempt = 0; attempt < 50; attempt++) {
            boolean[] matches = {false};
            scenario.onActivity(activity -> matches[0] = activity.getResources().getConfiguration().orientation == expected);
            if (matches[0]) return;
            SystemClock.sleep(100);
        }
        scenario.onActivity(activity -> assertEquals(expected,
                activity.getResources().getConfiguration().orientation));
    }

    private static void waitForSearchOrientation(ActivityScenario<MobileFastSearchActivity> scenario, int expected) {
        for (int attempt = 0; attempt < 50; attempt++) {
            boolean[] matches = {false};
            scenario.onActivity(activity -> matches[0] = activity.getResources().getConfiguration().orientation == expected);
            if (matches[0]) return;
            SystemClock.sleep(100);
        }
        scenario.onActivity(activity -> assertEquals(expected,
                activity.getResources().getConfiguration().orientation));
    }

    private static void waitForSearchActivityOrientation(ActivityScenario<MobileSearchActivity> scenario, int expected) {
        for (int attempt = 0; attempt < 50; attempt++) {
            boolean[] matches = {false};
            scenario.onActivity(activity -> matches[0] = activity.getResources().getConfiguration().orientation == expected);
            if (matches[0]) return;
            SystemClock.sleep(100);
        }
        scenario.onActivity(activity -> assertEquals(expected,
                activity.getResources().getConfiguration().orientation));
    }

    private static LayoutSnapshot requestOrientationAndAssertContent(ActivityScenario<HistoryActivity> scenario,
                                                                     int requestedOrientation,
                                                                     int expectedOrientation) {
        scenario.onActivity(activity -> activity.setRequestedOrientation(requestedOrientation));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        waitForOrientation(scenario, expectedOrientation);
        LayoutSnapshot[] snapshot = new LayoutSnapshot[1];
        scenario.onActivity(activity -> {
            View content = activity.findViewById(android.R.id.content);
            assertNotNull(content);
            assertTrue(content.getWidth() > 0);
            assertTrue(content.getHeight() > 0);
            RecyclerView recycler = activity.findViewById(R.id.recycler);
            assertNotNull(recycler);
            assertTrue(recycler.getWidth() > 0);
            assertTrue(recycler.getHeight() > 0);
            assertTrue(recycler.getLayoutManager() instanceof GridLayoutManager);
            GridLayoutManager manager = (GridLayoutManager) recycler.getLayoutManager();
            int[] spec = MobileProduct.getSpec(activity);
            assertEquals(MobileProduct.getColumn(activity), manager.getSpanCount());
            assertTrue(spec[0] > 0 && spec[0] * manager.getSpanCount() <= content.getWidth());
            snapshot[0] = new LayoutSnapshot(content.getWidth(), manager.getSpanCount(), spec[0],
                    MobileProduct.getNameTextSize());
        });
        assertNotNull(snapshot[0]);
        return snapshot[0];
    }

    private static void waitForOrientation(ActivityScenario<HistoryActivity> scenario, int expected) {
        for (int attempt = 0; attempt < 50; attempt++) {
            final boolean[] matches = {false};
            scenario.onActivity(activity -> matches[0] = activity.getResources().getConfiguration().orientation == expected);
            if (matches[0]) return;
            SystemClock.sleep(100);
        }
        scenario.onActivity(activity -> org.junit.Assert.assertEquals(
                expected, activity.getResources().getConfiguration().orientation));
    }

    private record LayoutSnapshot(int screenWidth, int spanCount, int itemWidth, int fontSize) {}

    private record PlayerSettingsSnapshot(String ua, int buffer, float speed, int render, int scale,
                                          int background, boolean caption, boolean tunnel,
                                          boolean audio, boolean video, boolean aac,
                                          boolean danmaku, boolean adblock) {
        static PlayerSettingsSnapshot capture() {
            return new PlayerSettingsSnapshot(PlayerSettings.getUa(), PlayerSettings.getBuffer(), PlayerSettings.getSpeed(),
                    PlayerSettings.getRender(), DisplaySettings.getScale(), PlayerSettings.getBackground(), PlayerSettings.isCaption(),
                    PlayerSettings.isTunnel(), PlayerSettings.isAudioPrefer(), PlayerSettings.isVideoPrefer(),
                    PlayerSettings.isPreferAAC(), PlayerSettings.isDanmakuLoad(), AppSettings.isAdblock());
        }

        void restore() {
            PlayerSettings.putUa(ua);
            PlayerSettings.putBuffer(buffer);
            PlayerSettings.putSpeed(speed);
            PlayerSettings.putRender(render);
            DisplaySettings.putScale(scale);
            PlayerSettings.putBackground(background);
            PlayerSettings.putCaption(caption);
            PlayerSettings.putTunnel(tunnel);
            PlayerSettings.putAudioPrefer(audio);
            PlayerSettings.putVideoPrefer(video);
            PlayerSettings.putPreferAAC(aac);
            PlayerSettings.putDanmakuLoad(danmaku);
            AppSettings.putAdblock(adblock);
        }
    }

    @Test
    public void keepActivityIsDeclaredAndEnabled() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        PackageManager packageManager = context.getPackageManager();
        ActivityInfo info = packageManager.getActivityInfo(
                new ComponentName(context, MobileKeepActivity.class), 0);
        assertNotNull(info);
        assertTrue(info.enabled);
    }

    private static final class RecordingActivity extends Activity {

        private Intent startedIntent;

        @Override
        public String getPackageName() {
            return "com.wuming.android.tv";
        }

        @Override
        public void startActivity(Intent intent) {
            startedIntent = intent;
        }
    }
}
