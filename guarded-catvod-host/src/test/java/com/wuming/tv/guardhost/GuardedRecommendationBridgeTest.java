package com.wuming.tv.guardhost;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import com.github.catvod.crawler.Spider;
import com.google.gson.Gson;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import org.junit.Test;
import static org.junit.Assert.*;

public class GuardedRecommendationBridgeTest {
    public static class Fixture extends Spider {
        final Context context;
        Fixture(Context context) { this.context = context; }
        @Override public String categoryContent(String id, String page, boolean filter, HashMap<String,String> extend) {
            String seeds = context.getSharedPreferences(context.getPackageName() + "_preferences", 0).getString("historyvodname", "missing");
            if ("fail".equals(page)) throw new IllegalStateException("fixture rejected category");
            return seeds;
        }
    }
    @Test public void wireSeedsReachProviderAndRestorePreferencesAfterSuccessAndFailure() throws Exception {
        Path dir = Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("user.home"), "temp")), "guarded-seeds-");
        try {
            Application context = new Application(dir.toFile());
            SharedPreferences prefs = context.getSharedPreferences(context.getPackageName() + "_preferences", 0);
            prefs.edit().putString("historyvodname", "previous-provider-state").commit();
            Class<?> requestClass = Class.forName("com.wuming.tv.guardhost.GuardedCatVodHostMain$Request");
            Method invoke = GuardedCatVodHostMain.class.getDeclaredMethod("invoke", Object.class, Context.class, requestClass);
            invoke.setAccessible(true);
            Gson gson = new Gson();
            Object request = gson.fromJson("{\"operation\":\"category\",\"id\":\"guess_you_like\",\"page\":\"1\",\"recommendationSeeds\":[\"First\",\"Second\"]}", requestClass);
            Fixture fixture = new Fixture(context);
            assertEquals("[\"First\",\"Second\"]", invoke.invoke(GuardedCatVodHostMain.INSTANCE, fixture, context, request));
            assertEquals("previous-provider-state", prefs.getString("historyvodname", ""));
            request = gson.fromJson("{\"operation\":\"category\",\"id\":\"guess_you_like\",\"page\":\"2\",\"recommendationSeeds\":[]}", requestClass);
            assertEquals("[]", invoke.invoke(GuardedCatVodHostMain.INSTANCE, fixture, context, request));
            request = gson.fromJson("{\"operation\":\"category\",\"id\":\"guess_you_like\",\"page\":\"fail\",\"recommendationSeeds\":[\"First\"]}", requestClass);
            final Object failureRequest = request;
            assertThrows(InvocationTargetException.class, () -> invoke.invoke(GuardedCatVodHostMain.INSTANCE, fixture, context, failureRequest));
            assertEquals("previous-provider-state", prefs.getString("historyvodname", ""));
            prefs.edit().remove("historyvodname").commit();
            request = gson.fromJson("{\"operation\":\"category\",\"id\":\"guess_you_like\",\"page\":\"1\",\"recommendationSeeds\":[\"First\"]}", requestClass);
            invoke.invoke(GuardedCatVodHostMain.INSTANCE, fixture, context, request);
            assertFalse(prefs.contains("historyvodname"));
        } finally {
            try (var files = Files.walk(dir)) { files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> { try { Files.delete(path); } catch (java.io.IOException e) { throw new RuntimeException(e); } }); }
        }
    }
}
