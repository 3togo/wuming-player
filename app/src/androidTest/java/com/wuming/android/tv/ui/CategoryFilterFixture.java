package com.wuming.android.tv.ui;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.fragment.app.Fragment;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.gson.Gson;
import com.wuming.android.tv.bean.FilterCache;
import com.wuming.android.tv.bean.Result;
import com.wuming.android.tv.bean.Site;
import com.wuming.android.tv.bean.Style;
import com.wuming.android.tv.bean.Vod;
import com.wuming.android.tv.model.SiteViewModel;
import com.wuming.android.tv.ui.activity.MemoryTestActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.Test;

/** Shared Android fixture; each flavor executes its actual folder builder and content renderer. */
public abstract class CategoryFilterFixture {
    protected abstract String folderClassName();
    protected abstract Fragment content(SiteViewModel model);
    protected abstract androidx.recyclerview.widget.RecyclerView.Adapter<?> tabs(
            List<com.wuming.android.tv.bean.Class> types, java.util.function.Consumer<com.wuming.android.tv.bean.Class> select);
    protected abstract void clickTab(androidx.recyclerview.widget.RecyclerView.ViewHolder holder);

    private com.wuming.android.tv.bean.Class category(String id, String defaults) {
        return new Gson().fromJson("{\"type_id\":\"" + id + "\",\"filters\":[{\"key\":\"type\","
                + defaults + "\"value\":[{\"n\":\"全部\",\"v\":\"\"},{\"n\":\"热门\",\"v\":\"tv_hot\"}]}]}",
                com.wuming.android.tv.bean.Class.class);
    }

    @SuppressWarnings("unchecked")
    private HashMap<String, String> request(com.wuming.android.tv.bean.Class type) throws Exception {
        Result result = new Result();
        result.setTypes(List.of(type));
        FilterCache.clear().put(result);
        java.lang.Class<?> folderClass = java.lang.Class.forName(folderClassName());
        Object folder = folderClass.getConstructor().newInstance();
        Field field = folderClass.getDeclaredField("mType");
        field.setAccessible(true);
        field.set(folder, type);
        Method method = folderClass.getDeclaredMethod("getExtend");
        method.setAccessible(true);
        return (HashMap<String, String>) method.invoke(folder);
    }

    @Test public void actualFolderBuilderOmitsMissingDefaultsButKeepsAllAndSelected() throws Exception {
        try {
            assertTrue(request(category("movie", "")).isEmpty());
            assertTrue(request(category("tv", "\"init\":null,")).isEmpty());
            assertEquals(java.util.Map.of("type", ""), request(category("all", "\"init\":\"\",")));
            assertEquals(java.util.Map.of("type", "tv_hot"), request(category("selected", "\"init\":\"tv_hot\",")));
        } finally { FilterCache.clear(); }
    }

    @Test public void categoryTransitionsRenderFreshContentAndActualPosterPixels() throws Exception {
        File directory = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),
                "category-regression-" + System.nanoTime());
        assertTrue(directory.mkdir());
        try (ActivityScenario<MemoryTestActivity> scenario = TestActivityLauncher.launch(MemoryTestActivity.class)) {
            String[] ids = {"movie", "tv", "variety", "all", "selected", "tv"};
            String[] defaults = {"", "\"init\":null,", "", "\"init\":\"\",", "\"init\":\"tv_hot\",", ""};
            java.util.ArrayList<com.wuming.android.tv.bean.Class> types = new java.util.ArrayList<>();
            for (int index = 0; index < ids.length; index++) {
                com.wuming.android.tv.bean.Class type = category(ids[index], defaults[index]);
                type.setTypeName(ids[index] + " " + index);
                types.add(type);
            }
            int containerId = View.generateViewId();
            androidx.recyclerview.widget.RecyclerView[] strip = new androidx.recyclerview.widget.RecyclerView[1];
            Runnable[] selection = new Runnable[1];
            com.wuming.android.tv.bean.Class[] selected = new com.wuming.android.tv.bean.Class[1];
            scenario.onActivity(activity -> {
                android.widget.LinearLayout root = new android.widget.LinearLayout(activity);
                root.setOrientation(android.widget.LinearLayout.VERTICAL);
                strip[0] = new androidx.recyclerview.widget.RecyclerView(activity);
                strip[0].setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(activity,
                        androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false));
                strip[0].setAdapter(tabs(types, type -> { selected[0] = type; selection[0].run(); }));
                root.addView(strip[0], new android.widget.LinearLayout.LayoutParams(-1, -2));
                android.widget.FrameLayout container = new android.widget.FrameLayout(activity);
                container.setId(containerId);
                root.addView(container, new android.widget.LinearLayout.LayoutParams(-1, 0, 1));
                ((ViewGroup) activity.findViewById(android.R.id.custom)).addView(root,
                        new ViewGroup.LayoutParams(-1, -1));
            });
            for (int index = 0; index < ids.length; index++) {
                String id = ids[index];
                String title = "Fixture " + id + " " + index;
                int color = Color.rgb(30 + index * 30, 220 - index * 20, 75 + index * 20);
                File poster = new File(directory, index + ".png");
                Bitmap bitmap = Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(color);
                try (FileOutputStream output = new FileOutputStream(poster)) {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
                } finally { bitmap.recycle(); }
                HashMap<String, String> extend = request(types.get(index));
                HashMap<String, String> expected = new HashMap<>();
                if (index == 3) expected.put("type", "");
                if (index == 4) expected.put("type", "tv_hot");
                assertEquals(expected, extend);
                Gateway gateway = new Gateway(id, title, poster, expected);
                SiteViewModel model = new SiteViewModel(gateway, keyword -> null);
                scenario.onActivity(activity -> {
                    selection[0] = () -> {
                        Fragment fragment = content(model);
                        Bundle args = new Bundle();
                        args.putString("key", "category-fixture");
                        args.putString("typeId", selected[0].getTypeId());
                        args.putParcelable("style", Style.rect());
                        args.putSerializable("extend", extend);
                        fragment.setArguments(args);
                        activity.getSupportFragmentManager().beginTransaction()
                                .replace(containerId, fragment).commitNow();
                    };
                });
                int position = index;
                scenario.onActivity(activity -> strip[0].scrollToPosition(position));
                boolean[] clicked = {false};
                long clickDeadline = SystemClock.elapsedRealtime() + 5000;
                while (!clicked[0] && SystemClock.elapsedRealtime() < clickDeadline) {
                    scenario.onActivity(activity -> {
                        androidx.recyclerview.widget.RecyclerView.ViewHolder holder = strip[0].findViewHolderForAdapterPosition(position);
                        if (holder != null) { clickTab(holder); clicked[0] = true; }
                    });
                    if (!clicked[0]) SystemClock.sleep(50);
                }
                assertTrue("Category tab was not available", clicked[0]);
                assertSame(types.get(index), selected[0]);
                boolean[] rendered = {false}, titlePresent = {false}, posterPresent = {false};
                long deadline = SystemClock.elapsedRealtime() + 10000;
                while (!rendered[0] && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity(activity -> {
                        titlePresent[0] = hasTitle(activity.findViewById(android.R.id.custom), title);
                        posterPresent[0] = hasPoster(activity.findViewById(android.R.id.custom), color);
                        rendered[0] = titlePresent[0] && posterPresent[0];
                    });
                    if (!rendered[0]) SystemClock.sleep(50);
                }
                assertTrue("Missing content after switching to " + title + ": title=" + titlePresent[0]
                        + " poster=" + posterPresent[0] + " requests=" + gateway.requests, rendered[0]);
                assertTrue("Category never reached provider", !gateway.requests.isEmpty());
                for (HashMap<String, String> actual : gateway.requests) assertEquals(expected, actual);
            }
        } finally {
            FilterCache.clear();
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) assertTrue(file.delete());
            assertTrue(directory.delete());
        }
    }

    private static boolean hasTitle(View view, String title) {
        // Production poster titles insert line breaks at spaces to fit narrow cards.
        if (view instanceof TextView && view.isShown()
                && title.equals(((TextView) view).getText().toString().replaceAll("\\s+", " ").trim())) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            if (hasTitle(((ViewGroup) view).getChildAt(i), title)) return true;
        return false;
    }

    private static boolean hasPoster(View view, int color) {
        if (view instanceof ImageView && view.isShown()) {
            Drawable drawable = ((ImageView) view).getDrawable();
            if (drawable != null) {
                Bitmap sample = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
                android.graphics.Rect bounds = new android.graphics.Rect(drawable.getBounds());
                try {
                    drawable.setBounds(0, 0, 16, 16);
                    drawable.draw(new android.graphics.Canvas(sample));
                    int pixel = sample.getPixel(8, 8);
                    // Rendering can round color channels; a placeholder still cannot match these fixtures.
                    if (Math.abs(Color.red(pixel) - Color.red(color)) <= 2
                            && Math.abs(Color.green(pixel) - Color.green(color)) <= 2
                            && Math.abs(Color.blue(pixel) - Color.blue(color)) <= 2
                            && Color.alpha(pixel) == 255) return true;
                } finally { drawable.setBounds(bounds); sample.recycle(); }
            }
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            if (hasPoster(((ViewGroup) view).getChildAt(i), color)) return true;
        return false;
    }

    private static final class Gateway implements SiteViewModel.ContentGateway {
        final String id, title;
        final File poster;
        final HashMap<String, String> expected;
        final CopyOnWriteArrayList<HashMap<String, String>> requests = new CopyOnWriteArrayList<>();
        Gateway(String id, String title, File poster, HashMap<String, String> expected) {
            this.id = id; this.title = title; this.poster = poster; this.expected = expected;
        }
        @Override public Result category(String key, String tid, String page, boolean filter, HashMap<String, String> extend) {
            requests.add(new HashMap<>(extend));
            // Reproduce the provider's empty result when an invented selection is sent.
            if (!id.equals(tid) || !expected.equals(extend)) return Result.empty();
            Vod vod = new Vod();
            vod.setId(id); vod.setName(title); vod.setPic(android.net.Uri.fromFile(poster).toString());
            Result result = new Gson().fromJson("{\"page\":1,\"pagecount\":1,\"total\":1}", Result.class);
            result.setList(List.of(vod));
            return result;
        }
        @Override public Result home() { return Result.empty(); }
        @Override public Result action(String key, String act) { return Result.empty(); }
        @Override public Result detail(String key, String id) { return Result.empty(); }
        @Override public Result player(String key, String flag, String id) { return Result.empty(); }
        @Override public Result search(Site site, String keyword, boolean quick, String page) { return Result.empty(); }
    }
}
