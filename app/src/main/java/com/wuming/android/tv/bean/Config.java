package com.wuming.android.tv.bean;



import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import com.wuming.android.tv.App;
import com.wuming.android.tv.db.AppDatabase;
import com.wuming.android.tv.db.AsyncDB;
import com.wuming.android.tv.db.DataCache;
import com.wuming.android.tv.library.UserLibrary;
import com.wuming.android.tv.utils.Task;
import com.github.catvod.Init;
import com.github.catvod.utils.Prefers;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import com.wuming.android.tv.utils.AppLog;

@Entity(indices = @Index(value = {"url", "type"}, unique = true))
public class Config implements com.wuming.tv.core.catalog.ConfigView {
    private static final String TAG = "Config";
    
    // An absent preferred source waits for explicit selection; never contact an unlisted URL.
    private static final String DEFAULT_VOD_URL = "";
    private static final String DEFAULT_VOD_NAME = "饭太硬";
    private static final String PUBLIC_SAMPLE_LIVE_URL = "assets://live/public_samples.m3u8";
    private static final String PUBLIC_SAMPLE_LIVE_NAME = "Open movie sample · CC BY 3.0";

    @PrimaryKey(autoGenerate = true)
    @SerializedName("id")
    private int id;
    @SerializedName("type")
    private int type;
    @SerializedName("time")
    private long time;
    @SerializedName("url")
    private String url;
    @SerializedName("json")
    private String json;
    @SerializedName("name")
    private String name;
    @SerializedName("logo")
    private String logo;
    @SerializedName("home")
    private String home;
    @SerializedName("parse")
    private String parse;
    @SerializedName("danmaku")
    private String danmaku;

    @Ignore
    @SerializedName("notice")
    private String notice;

    public static List<Config> arrayFrom(String str) {
        Type listType = new TypeToken<List<Config>>() {}.getType();
        List<Config> items = App.gson().fromJson(str, listType);
        return BeanUtils.emptyListIfNull(items);
    }

    public static Config objectFrom(String str) {
        return App.gson().fromJson(str, Config.class);
    }

    public static Config create(int type) {
        AppLog.d(TAG, "create: type=" + type);
        return new Config().type(type);
    }

    public static Config create(int type, String url) {
        AppLog.d(TAG, "create: type=" + type);
        return new Config().type(type).url(url).insert();
    }

    public static Config create(int type, String url, String name) {
        AppLog.d(TAG, "create: type=" + type + ", name='" + name + "'");
        return new Config().type(type).url(url).name(name).insert();
    }

    public static List<Config> getAll(int type) {
        // Read from in-memory cache (no DB access on main thread)
        if (DataCache.isReady()) {
            return DataCache.get().getConfigsByType(type);
        }
        // Fallback: cache not ready yet
        List<Config> items = AppDatabase.get().getConfigDao().findByType(type);
        return items;
    }

    public static List<Config> findUrls() {
        // Read from in-memory cache (no DB access on main thread)
        if (DataCache.isReady()) {
            return DataCache.get().getVodConfigUrls();
        }
        // Fallback: cache not ready yet
        return AppDatabase.get().getConfigDao().findUrlByType(0);
    }

    public static void delete(String url) {
        if (DataCache.isReady()) DataCache.get().removeConfig(url);
        else AppDatabase.get().getConfigDao().delete(url);
    }

    public static void delete(String url, int type) {
        if (DataCache.isReady()) DataCache.get().removeConfig(url, type);
        else AppDatabase.get().getConfigDao().delete(url, type);
    }

    // Async versions for UI thread safety

    public static void getAllAsync(int type, java.util.function.Consumer<List<Config>> callback) {
        AsyncDB.query(() -> getAll(type), callback);
    }

    public static void getAllAsyncSafe(int type, java.lang.ref.WeakReference<java.util.function.Consumer<List<Config>>> callbackRef) {
        AsyncDB.query(() -> getAll(type), result -> {
            java.util.function.Consumer<List<Config>> callback = callbackRef.get();
            if (callback != null) callback.accept(result);
        });
    }

    public static void findUrlsAsync(java.util.function.Consumer<List<Config>> callback) {
        AsyncDB.query(Config::findUrls, callback);
    }

    public static void findUrlsAsyncSafe(java.lang.ref.WeakReference<java.util.function.Consumer<List<Config>>> callbackRef) {
        AsyncDB.query(Config::findUrls, result -> {
            java.util.function.Consumer<List<Config>> callback = callbackRef.get();
            if (callback != null) callback.accept(result);
        });
    }

    public static void findAsync(int id, java.util.function.Consumer<Config> callback) {
        AsyncDB.query(() -> find(id), callback);
    }

    public static void findAsync(String url, int type, java.util.function.Consumer<Config> callback) {
        AsyncDB.query(() -> find(url, type), callback);
    }

    public static void findAsync(String url, String name, int type, java.util.function.Consumer<Config> callback) {
        AsyncDB.query(() -> find(url, name, type), callback);
    }

    public static void deleteAsync(String url) {
        AsyncDB.execute(() -> delete(url));
    }

    public static void deleteAsync(String url, int type) {
        AsyncDB.execute(() -> delete(url, type));
    }

    public static void vodAsync(java.util.function.Consumer<Config> callback) {
        AsyncDB.query(Config::vod, callback);
    }

    public static void vodAsyncSafe(java.lang.ref.WeakReference<java.util.function.Consumer<Config>> callbackRef) {
        AsyncDB.query(Config::vod, result -> {
            java.util.function.Consumer<Config> callback = callbackRef.get();
            if (callback != null) callback.accept(result);
        });
    }

    public static void liveAsync(java.util.function.Consumer<Config> callback) {
        AsyncDB.query(Config::live, callback);
    }

    public static void liveAsyncSafe(java.lang.ref.WeakReference<java.util.function.Consumer<Config>> callbackRef) {
        AsyncDB.query(Config::live, result -> {
            java.util.function.Consumer<Config> callback = callbackRef.get();
            if (callback != null) callback.accept(result);
        });
    }

    public static void wallAsync(java.util.function.Consumer<Config> callback) {
        AsyncDB.query(Config::wall, callback);
    }

    public static void wallAsyncSafe(java.lang.ref.WeakReference<java.util.function.Consumer<Config>> callbackRef) {
        AsyncDB.query(Config::wall, result -> {
            java.util.function.Consumer<Config> callback = callbackRef.get();
            if (callback != null) callback.accept(result);
        });
    }

    private static boolean isMainThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    public static synchronized Config vod() {
        // Read from in-memory cache (no DB access on main thread)
        if (DataCache.isReady()) {
            Config item = DataCache.get().getActiveConfig(0);
            if (item != null && BeanUtils.isNotEmpty(item.getUrl())) return item;
        }
        // The cache bootstrap runs on a background thread and may not be ready when a
        // UI path first asks for the config. Reading Room from the main thread is
        // forbidden (allowMainThreadQueries() was removed) and crashes startup.
        // Return an in-memory default; the background config load resolves the real
        // stored config and refreshes the UI through events (see VodFragment.onRefreshEvent).
        if (isMainThread()) {
            return create(0).url(DEFAULT_VOD_URL).name(DEFAULT_VOD_NAME);
        }
        // Fallback: cache not ready yet, load from DB (background thread during init)
        Config item = AppDatabase.get().getConfigDao().findOne(0);
        if (item == null || BeanUtils.isEmpty(item.getUrl())) {
            defaultsSync();
            item = AppDatabase.get().getConfigDao().findOne(0);
            if (item == null || BeanUtils.isEmpty(item.getUrl())) {
                item = create(0, DEFAULT_VOD_URL, DEFAULT_VOD_NAME);
                item.update();
            }
        }
        if (DataCache.isReady()) DataCache.get().setActiveConfig(0, item);
        return item;
    }

    public static void defaults() {
        // Load defaults asynchronously - for use in settings UI
        Task.execute(() -> {
            try {
                defaults("vod", 0, DEFAULT_VOD_NAME, false);
                defaults("live", 1, null, false);
            } catch (Exception e) {
                AppLog.e(TAG, e);
            }
        });
    }

    public static void defaultsSync() {
        // Load defaults synchronously - for use during app initialization
        // Called on a background thread before the startup cache is populated
        try {
            defaults("vod", 0, DEFAULT_VOD_NAME, false);
            defaults("live", 1, null, false);
            ensureStoredLiveSources();
        } catch (Exception e) {
            AppLog.e(TAG, e);
        }
    }

    private static void ensureStoredLiveSources() {
        find(PUBLIC_SAMPLE_LIVE_URL, PUBLIC_SAMPLE_LIVE_NAME, 1);
    }

    public static void defaultsForce() {
        // Load defaults asynchronously - for use in settings UI
        Task.execute(() -> {
            try {
                defaults("vod", 0, DEFAULT_VOD_NAME, true);
                defaults("live", 1, null, true);
            } catch (Exception e) {
                AppLog.e(TAG, e);
            }
        });
    }

    private static synchronized void defaults(String key, int type, String defaultName, boolean force) {
        if (!force && !AppDatabase.get().getConfigDao().findByType(type).isEmpty()) return;
        Config defaultConfig = null;
        try {
            String json;
            try (InputStream is = Init.context().getAssets().open("depot.json")) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] data = new byte[1024];
                int nRead;
                while ((nRead = is.read(data, 0, data.length)) != -1) {
                    buffer.write(data, 0, nRead);
                }
                json = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            }
            List<Depot> items = Depot.arrayFrom(com.google.gson.JsonParser.parseString(json).getAsJsonObject().getAsJsonArray(key).toString());
            java.util.List<com.wuming.tv.core.source.config.BundledStartupPolicy.Source> sources = new java.util.ArrayList<>();
            for (Depot item : items) {
                sources.add(new com.wuming.tv.core.source.config.BundledStartupPolicy.Source(item.getName(), item.getUrl()));
            }
            com.wuming.tv.core.source.config.BundledStartupPolicy.Source preferred = defaultName == null ? null :
                    com.wuming.tv.core.source.config.BundledStartupPolicy.select(sources, defaultName);
            for (Depot item : items) {
                Config config = Config.find(item, type);
                if (preferred != null && preferred.getLocation().equals(item.getUrl())) defaultConfig = config;
            }
        } catch (Exception e) {
            AppLog.e(TAG, e);
        }
        // A missing or unreadable depot must never select an unrelated VOD source.
        if (type == 0 && (defaultConfig == null || defaultConfig.isEmpty())) {
            defaultConfig = find(DEFAULT_VOD_URL, DEFAULT_VOD_NAME, 0);
        }
        if (defaultConfig != null) defaultConfig.update();
    }

    public static Config live() {
        // Read from in-memory cache (no DB access on main thread)
        if (DataCache.isReady()) {
            Config item = DataCache.get().getActiveConfig(1);
            if (item != null && BeanUtils.isNotEmpty(item.getUrl())) return item;
        }
        // Mirror vod(): never read Room from the main thread (startup crash risk).
        if (isMainThread()) {
            return create(1, "", "No Config");
        }
        // Fallback: cache not ready yet
        Config item = AppDatabase.get().getConfigDao().findOne(1);
        if (item == null || BeanUtils.isEmpty(item.getUrl())) {
            defaultsSync();
            item = AppDatabase.get().getConfigDao().findOne(1);
            if (item == null || BeanUtils.isEmpty(item.getUrl())) {
                item = create(1, "", "No Config");
            }
        }
        if (DataCache.isReady()) DataCache.get().setActiveConfig(1, item);
        return item;
    }

    public static Config wall() {
        // An empty wall config is valid. Once the cache has loaded, returning
        // that value is both authoritative and safe; falling through to Room
        // would perform a blocking query from callers such as Settings.
        if (DataCache.isReady()) {
            Config item = DataCache.get().getActiveConfig(2);
            if (item != null) return item;
        }

        // Settings can be displayed before the asynchronous database/cache
        // bootstrap completes. Never query Room from that UI path. The empty
        // value is transient; background loading still resolves the persisted
        // wall configuration below.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return create(2).name("No Config");
        }

        // Background-only fallback while the cache is not ready yet.
        Config item = AppDatabase.get().getConfigDao().findOne(2);
        if (item == null || BeanUtils.isEmpty(item.getUrl())) {
            defaultsSync();
            item = AppDatabase.get().getConfigDao().findOne(2);
            if (item == null || BeanUtils.isEmpty(item.getUrl())) {
                item = create(2, "", "No Config");
            }
        }
        if (DataCache.isReady()) DataCache.get().setActiveConfig(2, item);
        return item;
    }

    public static Config find(int id) {
        if (DataCache.isReady()) {
            Config item = DataCache.get().findConfigById(id);
            if (item != null) return item;
        }
        return AppDatabase.get().getConfigDao().findById(id);
    }

    public static Config find(String url, int type) {
        if (DataCache.isReady()) {
            Config item = DataCache.get().findConfig(url, type);
            return item == null ? create(type, url) : item.type(type);
        }
        Config item = AppDatabase.get().getConfigDao().find(url, type);
        return item == null ? create(type, url) : item.type(type);
    }

    public static Config find(String url, String name, int type) {
        Config item;
        if (DataCache.isReady()) {
            item = DataCache.get().findConfig(url, type);
        } else {
            item = AppDatabase.get().getConfigDao().find(url, type);
        }
        if (item == null) {
            return create(type, url, name);
        } else {
            if (BeanUtils.isNotEmpty(name) && !name.equals(item.getName())) {
                item.type(type).name(name).save();
            } else {
                item.type(type);
            }
            return item;
        }
    }

    public static Config find(Config config) {
        return find(config, config.getType());
    }

    public static Config find(Config config, int type) {
        Config item;
        if (DataCache.isReady()) {
            item = DataCache.get().findConfig(config.getUrl(), type);
        } else {
            item = AppDatabase.get().getConfigDao().find(config.getUrl(), type);
        }
        if (item == null) {
            return create(type, config.getUrl(), config.getName());
        } else {
            String configName = config.getName();
            if (BeanUtils.isNotEmpty(configName) && !configName.equals(item.getName())) {
                item.type(type).name(configName).save();
            } else {
                item.type(type);
            }
            return item;
        }
    }

    public static Config find(Depot depot, int type) {
        Config item;
        if (DataCache.isReady()) {
            item = DataCache.get().findConfig(depot.getUrl(), type);
        } else {
            item = AppDatabase.get().getConfigDao().find(depot.getUrl(), type);
        }
        if (item == null) {
            return create(type, depot.getUrl(), depot.getName());
        } else {
            String depotName = depot.getName();
            if (BeanUtils.isNotEmpty(depotName) && !depotName.equals(item.getName())) {
                item.type(type).name(depotName).save();
            } else {
                item.type(type);
            }
            return item;
        }
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public String getUrl() {
        return BeanUtils.emptyIfNull(url);
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getJson() {
        return BeanUtils.emptyIfNull(json);
    }

    public void setJson(String json) {
        this.json = json;
    }

    public String getName() {
        return BeanUtils.emptyIfNull(name);
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLogo() {
        return BeanUtils.emptyIfNull(logo);
    }

    public void setLogo(String logo) {
        this.logo = logo;
    }

    public String getHome() {
        return BeanUtils.emptyIfNull(home);
    }

    public void setHome(String home) {
        this.home = home;
    }

    public String getParse() {
        return BeanUtils.emptyIfNull(parse);
    }

    public void setParse(String parse) {
        this.parse = parse;
    }

    public String getDanmaku() {
        return BeanUtils.emptyIfNull(danmaku);
    }

    public void setDanmaku(String danmaku) {
        this.danmaku = danmaku;
    }

    public long getTime() {
        return time;
    }

    public void setTime(long time) {
        this.time = time;
    }

    public String getNotice() {
        return BeanUtils.emptyIfNull(notice);
    }

    public void setNotice(String notice) {
        this.notice = notice;
    }

    public Config type(int type) {
        setType(type);
        return this;
    }

    public Config url(String url) {
        setUrl(url);
        return this;
    }

    public Config json(String json) {
        setJson(json);
        return this;
    }

    public Config name(String name) {
        setName(name);
        return this;
    }

    public boolean isEmpty() {
        return BeanUtils.isEmpty(getUrl());
    }

    public String getDesc() {
        String result;
        if (BeanUtils.isNotEmpty(getName())) {
            result = getName();
        } else if (BeanUtils.isNotEmpty(getUrl())) {
            result = getUrl();
        } else {
            // Fallback for empty configs - show type to avoid invisible content
            result = "Config #" + getId() + " (Type: " + getType() + ")";
        }
        android.util.Log.d("Config", "getDesc: id=" + id + ", name='" + getName() + "', url='" + getUrl() + "', desc='" + result + "'");
        return result;
    }

    public Config insert() {
        if (isEmpty()) return this;
        if (DataCache.isReady()) {
            // Use a temporary negative ID; the real ID will be set after async DB insert
            setId(-System.identityHashCode(this));
            DataCache.get().putConfig(this);
            Config self = this;
            Task.execute(() -> {
                self.setId(Math.toIntExact(AppDatabase.get().getConfigDao().insert(self)));
                DataCache.get().putConfig(self);
            });
        } else {
            setId(Math.toIntExact(AppDatabase.get().getConfigDao().insert(this)));
        }
        return this;
    }

    public Config save() {
        if (isEmpty()) return this;
        if (DataCache.isReady()) DataCache.get().putConfig(this);
        else AppDatabase.get().getConfigDao().insertOrUpdate(this);
        return this;
    }

    public void saveAsync() {
        if (isEmpty()) return;
        Config self = this;
        AsyncDB.execute(() -> AppDatabase.get().getConfigDao().insertOrUpdate(self));
    }

    public Config update() {
        if (isEmpty()) return this;
        setTime(System.currentTimeMillis());
        Prefers.put("config_" + getType(), getUrl());
        if (DataCache.isReady()) {
            DataCache.get().setActiveConfig(getType(), this);
            DataCache.get().putConfig(this);
        } else {
            save();
        }
        return this;
    }

    public void delete() {
        if (DataCache.isReady()) DataCache.get().removeConfig(getUrl(), getType());
        else AppDatabase.get().getConfigDao().delete(getUrl(), getType());
        UserLibrary.get().deleteHistoryCatalog(getId());
        UserLibrary.get().deleteVideoCatalog(getId());
    }

    public void deleteAsync() {
        Config self = this;
        AsyncDB.execute(() -> {
            AppDatabase.get().getConfigDao().delete(self.getUrl(), self.getType());
            UserLibrary.get().deleteHistoryCatalog(self.getId());
            UserLibrary.get().deleteVideoCatalog(self.getId());
        });
    }

    @NonNull
    @Override
    public String toString() {
        return App.gson().toJson(this);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Config)) return false;

        Config it = (Config) obj;
        return getId() == it.getId();
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getId());
    }
}
