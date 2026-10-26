package com.wuming.android.tv;
import com.wuming.android.tv.setting.AppSettings;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.wuming.android.tv.bean.Config;
import com.wuming.android.tv.browse.VodBrowse;
import com.wuming.android.tv.db.AppDatabase;
import com.wuming.android.tv.db.DataCache;
import com.wuming.android.tv.recommendation.RecommendationEngine;
import com.wuming.android.tv.server.Server;
import com.wuming.android.tv.setting.UiTheme;
import com.wuming.android.tv.utils.AppLog;
import com.wuming.android.tv.utils.ImgUtil;
import com.wuming.android.tv.utils.Notify;
import com.wuming.android.tv.utils.NetworkEnvironmentMonitor;
import com.wuming.android.tv.utils.SystemToastInterceptor;
import com.wuming.android.tv.utils.StoragePressureManager;
import com.wuming.android.tv.utils.Task;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;
import com.github.catvod.Init;
import com.google.gson.Gson;

import java.io.FileInputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;

public class App extends Application implements Application.ActivityLifecycleCallbacks, ComponentCallbacks2 {

    private static final String TAG = "App";

    private static volatile App instance;

    private final Handler handler;
    private final Gson gson;
    private final long time;

    private volatile WeakReference<Activity> activityRef;

    public App() {
        instance = this;
        Init.set(this);
        gson = new Gson();
        time = System.currentTimeMillis();
        handler = new Handler(Looper.getMainLooper()) {
            @Override
            public void dispatchMessage(@NonNull android.os.Message msg) {
                try {
                    super.dispatchMessage(msg);
                } catch (RuntimeException e) {
                    handleMainThreadCrash(e);
                }
            }
        };
        // Global fallback: catch crashes from spider's own Handler (bypasses App.post).
        // Keep the previously installed handler in the chain: swallowing an
        // exception on a worker thread leaves the process in an unknown state
        // and prevents Android/crash tooling from recording the real failure.
        Thread.UncaughtExceptionHandler previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            if (isProviderProcess()) {
                AppLog.e(TAG, "Provider process crashed on thread " + t.getName(), e);
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(11);
            } else if (t == Looper.getMainLooper().getThread()) {
                handleMainThreadCrash(e);
            } else {
                AppLog.e(TAG, "Uncaught exception in thread " + t.getName(), e);
                if (previousHandler != null) previousHandler.uncaughtException(t, e);
            }
        });
    }

    /**
     * Handle crashes on the main thread gracefully: set the crash flag
     * so spider methods return empty instead of re-crashing, show a toast,
     * and restart the process cleanly.
     */
    private static void handleMainThreadCrash(Throwable e) {
        AppLog.e(TAG, "Main thread crash caught", e);
        // Set crash flag so spider calls return empty on next attempt
        try {
            Prefers.put("crash", true);
        } catch (Throwable ignored) {
        }
        try {
            Notify.show("ERROR: Spider crashed. Restarting...");
        } catch (Throwable ignored) {
        }
        // Allow logs to flush, then restart
        try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(10);
    }

    public static App get() {
        return instance;
    }

    public static Gson gson() {
        return get().gson;
    }

    public static long time() {
        return get().time;
    }

    public static Activity activity() {
        WeakReference<Activity> ref = get().activityRef;
        return ref != null ? ref.get() : null;
    }

    @Nullable
    public static Activity activeActivity() {
        Activity activity = activity();
        return activity != null && !activity.isFinishing() && !activity.isDestroyed() ? activity : null;
    }

    public static boolean isProviderProcess() {
        String processName = processName();
        return processName != null && processName.endsWith(":provider");
    }

    private static boolean isMainProcess() {
        String processName = processName();
        return processName == null || processName.isEmpty() || processName.equals(get().getPackageName());
    }

    private static volatile String cachedProcessName;

    @Nullable
    private static String processName() {
        if (cachedProcessName != null) return cachedProcessName;
        String processName;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            processName = Application.getProcessName();
        } else {
            processName = "";
            int pid = android.os.Process.myPid();
            ActivityManager manager = (ActivityManager) get().getSystemService(Context.ACTIVITY_SERVICE);
            java.util.List<ActivityManager.RunningAppProcessInfo> processes = manager == null ? null : manager.getRunningAppProcesses();
            if (processes != null) {
                for (ActivityManager.RunningAppProcessInfo process : processes) {
                    if (process.pid == pid) {
                        processName = process.processName;
                        break;
                    }
                }
            }
            if (processName.isEmpty()) {
                try (FileInputStream input = new FileInputStream("/proc/self/cmdline")) {
                    byte[] buffer = new byte[256];
                    int length = input.read(buffer);
                    if (length > 0) {
                        int end = 0;
                        while (end < length && buffer[end] != 0) end++;
                        processName = new String(buffer, 0, end, StandardCharsets.UTF_8);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        cachedProcessName = processName;
        return processName;
    }

    public static void post(Runnable runnable) {
        get().handler.post(runnable);
    }

    public static final java.util.concurrent.Executor mainThreadExecutor = runnable -> post(runnable);

    public static void post(Runnable runnable, long delayMillis) {
        get().handler.removeCallbacks(runnable);
        if (delayMillis >= 0) get().handler.postDelayed(runnable, delayMillis);
    }

    public static void removeCallbacks(Runnable runnable) {
        get().handler.removeCallbacks(runnable);
    }

    public static void removeCallbacks(Runnable... runnable) {
        for (Runnable r : runnable) get().handler.removeCallbacks(r);
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Init.set(base);
        // External providers can create WebViews. Android 9+ forbids two app
        // processes from sharing the default WebView data directory, so give
        // the provider process its own directory before content providers or
        // any provider code can initialize Chromium.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P && isProviderProcess()) {
            WebView.setDataDirectorySuffix("provider");
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // AndroidX Startup providers run in the app's default process only.
        // Initialize OkHttp's Android platform explicitly in the provider
        // process so cookie/public-suffix handling can read packaged assets.
        boolean providerProcess = isProviderProcess();
        if (providerProcess) okhttp3.OkHttp.INSTANCE.initialize(this);
        // Crash/error activities run in a lightweight process. They must not
        // initialize the database or compete with the main process for the
        // local HTTP server port.
        if (!providerProcess && !isMainProcess()) return;
        // Both UI and isolated site-provider processes must redirect toasts.
        // Provider notices are relayed to the UI's bottom popup.
        SystemToastInterceptor.install();
        // The provider process only needs app context, cookies, networking, and
        // private credential-file access. UI/database/server startup belongs to
        // the main process and would otherwise duplicate work or contend for ports.
        if (providerProcess) return;
        Task.execute(() -> {
            try {
                com.wuming.android.tv.edition.AppExtensions.getCurrent().initialize();
            } catch (Exception error) {
                AppLog.e(TAG, "Edition initialization failed", error);
            }
        });
        UiTheme.applySavedAppearance();
        Notify.createChannel();
        NetworkEnvironmentMonitor.start(this);
        registerActivityLifecycleCallbacks(this);
        // Initialize database and config asynchronously to avoid blocking main thread
        Task.execute(() -> {
            // Casting must remain available even when unrelated database/catalog maintenance
            // fails. Start the receiver before that work and outside its failure boundary.
            Server.get().start();
            try {
                // Initialize database in background
                com.wuming.android.tv.utils.OpenDriveQrHandoff.cleanupExpired(this);
                AppDatabase.get();
                // Overlap recommendation-profile I/O with the slower startup/config work.
                RecommendationEngine.prefetch();
                new com.wuming.android.tv.media.catalog.RoomMediaCatalog().prune();
                StoragePressureManager.relieve(this);
                // Initialize in-memory cache from DB (must be after AppDatabase init)
                DataCache.get().init();
                handleRestartMode();
            } catch (Exception e) {
                AppLog.e(TAG, "Background init failed", e);
            }
        });

    }

    /**
     * Handle restart mode - clear session or all data if requested.
     * This is called at app startup before any activity is created.
     * Synchronized to prevent race conditions if called from multiple threads.
     */
    private synchronized void handleRestartMode() {
        int mode = AppSettings.consumeRestartMode();
        if (AppSettings.shouldClearSession(mode)) {
            // Clear session data (keep history)
            clearSessionData();
            if (AppSettings.shouldClearAll(mode)) {
                // Clear all data including history
                clearAllData();
            }
        }
    }

    /**
     * Clear session data but keep history.
     * This resets settings that were changed during the session.
     */
    private void clearSessionData() {
        // Reset only app-owned session settings. Provider credentials share the
        // same preference file and must survive app/process upgrades.
        AppSettings.clearSessionSettings();
        // Note: History is kept
    }

    /**
     * Clear all data including history.
     */
    private void clearAllData() {
        // Clear history
        AppDatabase.get().getHistoryDao().delete();
        // Clear keep (favorites)
        AppDatabase.get().getKeepDao().deleteAll();
        // Clear search history
        AppDatabase.get().getSearchHistoryDao().deleteAll();
        // Reload cache to reflect cleared data
        DataCache.get().reload();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        handleMemoryPressure(level);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onLowMemory() {
        super.onLowMemory();
        handleMemoryPressure(ComponentCallbacks2.TRIM_MEMORY_COMPLETE);
    }

    @SuppressWarnings("deprecation")
    private void handleMemoryPressure(int level) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            com.wuming.android.tv.api.SiteApi.clearDetailCache();
            com.wuming.android.tv.browse.LiveBrowse.trim();
            com.wuming.android.tv.bean.FilterCache.clear();
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            VodBrowse.clear();
            com.wuming.android.tv.db.DataCache.get().trimHistories(
                    com.wuming.tv.core.cache.MemoryBudget.current().scale(50, 100, 200));
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            ImgUtil.clearMemory();
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            com.github.catvod.net.OkHttp.get().clear();
            com.wuming.android.tv.server.process.DesktopCatalogSessions.clear();
            com.wuming.android.tv.server.process.CastMediaRelay.clearAggregatedCache();
        }
    }

    @Override
    public PackageManager getPackageManager() {
        return getBaseContext().getPackageManager();
    }

    @Override
    public String getPackageName() {
        return getBaseContext().getPackageName();
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        // Update activity reference when any activity resumes
        this.activityRef = new WeakReference<>(activity);
        // Flush any startup toast raised before an activity was available.
        Notify.onActivityResumed();
        Task.execute(() -> StoragePressureManager.relieve(this));
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
        // Don't clear reference on pause to avoid NPE during configuration changes
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
        // Clear reference when activity is destroyed to prevent memory leaks
        WeakReference<Activity> ref = activityRef;
        if (ref != null) {
            Activity referencedActivity = ref.get();
            if (referencedActivity == null || referencedActivity == activity) {
                activityRef = null;
            }
        }
        // Clear any Notify dialogs associated with this activity to prevent singleton leaks
        Notify.onActivityDestroyed(activity);
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
    }
}
