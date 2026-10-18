package com.wuming.android.tv.bean;

import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

import com.wuming.android.tv.App;
import com.wuming.android.tv.Constant;
import com.wuming.android.tv.api.loader.BaseLoader;
import com.wuming.android.tv.db.AsyncDB;
import com.wuming.android.tv.db.AppDatabase;
import com.wuming.android.tv.db.DataCache;
import com.wuming.android.tv.gson.ExtAdapter;
import com.wuming.android.tv.gson.HeaderAdapter;
import com.wuming.android.tv.utils.UrlUtil;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Trans;
import com.google.gson.JsonElement;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Entity
public class Site implements Parcelable, com.wuming.tv.core.catalog.SiteView {

    // IMPORTANT: When adding, removing, or reordering fields, you MUST update
    // both writeToParcel(Parcel, int) and the Site(Parcel) constructor together.
    // The read/write order must be identical to preserve Parcel integrity.

    @NonNull
    @PrimaryKey
    @SerializedName("key")
    private String key;

    @Ignore
    @SerializedName("name")
    private String name;

    @Ignore
    @SerializedName("api")
    private String api;

    @Ignore
    @SerializedName("ext")
    @JsonAdapter(ExtAdapter.class)
    private String ext;

    @Ignore
    @SerializedName("jar")
    private String jar;

    @Ignore
    @SerializedName("click")
    private String click;

    @Ignore
    @SerializedName("playUrl")
    private String playUrl;

    @Ignore
    @SerializedName("type")
    private Integer type;

    @Ignore
    @SerializedName("hide")
    private Integer hide;

    @Ignore
    @SerializedName("indexs")
    private Integer indexs;

    @Ignore
    @SerializedName("timeout")
    private Integer timeout;

    @SerializedName("searchable")
    private Integer searchable;

    @SerializedName("changeable")
    private Integer changeable;

    @Ignore
    @SerializedName("quickSearch")
    private Integer quickSearch;

    @Ignore
    @SerializedName("categories")
    private List<String> categories;

    @Ignore
    @SerializedName("header")
    @JsonAdapter(HeaderAdapter.class)
    private Map<String, String> header;

    @Ignore
    @SerializedName("style")
    private Style style;

    @Ignore
    private boolean activated;

    public Site() {
    }

    @SuppressWarnings("deprecation")
    protected Site(Parcel in) {
        this.key = in.readString();
        this.name = in.readString();
        this.api = in.readString();
        this.ext = in.readString();
        this.jar = in.readString();
        this.click = in.readString();
        this.playUrl = in.readString();
        this.type = (Integer) in.readValue(Integer.class.getClassLoader());
        this.hide = (Integer) in.readValue(Integer.class.getClassLoader());
        this.indexs = (Integer) in.readValue(Integer.class.getClassLoader());
        this.timeout = (Integer) in.readValue(Integer.class.getClassLoader());
        this.searchable = (Integer) in.readValue(Integer.class.getClassLoader());
        this.changeable = (Integer) in.readValue(Integer.class.getClassLoader());
        this.quickSearch = (Integer) in.readValue(Integer.class.getClassLoader());
        this.categories = in.createStringArrayList();
        this.style = in.readParcelable(Style.class.getClassLoader());
        this.activated = in.readByte() != 0;
        String headerJson = in.readString();
        if (headerJson != null) {
            try {
                java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<Map<String, String>>(){}.getType();
                this.header = App.gson().fromJson(headerJson, type);
            } catch (Exception e) { Log.w("Site", "Parcel header failed", e); }
        }
    }

    public static Site objectFrom(JsonElement element, String spider) {
        try {
            Site site = App.gson().fromJson(element, Site.class);
            if (site.getJar().isEmpty()) site.setJar(spider);
            site.setApi(UrlUtil.convert(site.getApi()));
            site.setExt(UrlUtil.convert(site.getExt()));
            return site.trans();
        } catch (Exception e) {
            return new Site();
        }
    }

    public static Site get(String key, String name) {
        Site site = new Site();
        site.setKey(key);
        site.setName(name);
        return site;
    }

    public static Site builtinSpider(String key, String name, String api) {
        Site site = get(key, name);
        site.api = api;
        site.jar = "";
        site.ext = "";
        site.type = 3;
        site.searchable = 0;
        site.changeable = 0;
        site.quickSearch = 0;
        return site;
    }

    public static List<Site> findAll() {
        if (DataCache.isReady()) return DataCache.get().getSites();
        return AppDatabase.get().getSiteDao().findAll();
    }

    public static void findAllAsync(java.util.function.Consumer<List<Site>> callback) {
        AsyncDB.query(Site::findAll, callback);
    }

    public static void findAllAsyncSafe(WeakReference<java.util.function.Consumer<List<Site>>> callbackRef) {
        AsyncDB.query(Site::findAll, result -> {
            java.util.function.Consumer<List<Site>> callback = callbackRef.get();
            if (callback != null) {
                callback.accept(result);
            }
        });
    }

    public String getKey() {
        return BeanUtils.emptyIfNull(key);
    }

    public void setKey(@NonNull String key) {
        this.key = key;
    }

    public String getName() {
        return BeanUtils.emptyIfNull(name);
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getApi() {
        return BeanUtils.emptyIfNull(api);
    }

    public void setApi(String api) {
        this.api = api;
    }

    public String getExt() {
        return BeanUtils.emptyIfNull(ext);
    }

    public void setExt(String ext) {
        this.ext = ext.trim();
    }

    public String getJar() {
        return BeanUtils.emptyIfNull(jar);
    }

    public void setJar(String jar) {
        this.jar = jar;
    }

    public String getClick() {
        return BeanUtils.emptyIfNull(click);
    }

    public String getPlayUrl() {
        return BeanUtils.emptyIfNull(playUrl);
    }

    public Integer getType() {
        return BeanUtils.getOrDefault(type, 0);
    }

    public Integer getHide() {
        return BeanUtils.getOrDefault(hide, 0);
    }

    public Integer getIndexs() {
        return BeanUtils.getOrDefault(indexs, 0);
    }

    public long getTimeout() {
        return BeanUtils.toMillisTimeout(timeout, Constant.TIMEOUT_PLAY);
    }

    public Integer getSearchable() {
        return BeanUtils.getOrDefault(searchable, 1);
    }

    public Integer getChangeable() {
        return BeanUtils.getOrDefault(changeable, 1);
    }

    public Integer getQuickSearch() {
        return BeanUtils.getOrDefault(quickSearch, 1);
    }

    public List<String> getCategories() {
        return BeanUtils.emptyListIfNull(categories);
    }

    public void setCategories(List<String> categories) {
        this.categories = categories;
    }

    public Map<String, String> getHeader() {
        return BeanUtils.emptyMapIfNull(header);
    }

    public Style getStyle() {
        return style;
    }

    public Style getStyle(Style fallback) {
        if (getStyle() != null) return getStyle();
        if (fallback != null) return fallback;
        return Style.rect();
    }

    public boolean isActivated() {
        return activated;
    }

    public void setActivated(boolean activated) {
        this.activated = activated;
    }

    public void setActivated(Site item) {
        setActivated(item != null && item.equals(this));
    }

    public boolean isHide() {
        return BeanUtils.isEnabled(hide);
    }

    public boolean isIndex() {
        return BeanUtils.isEnabled(indexs);
    }

    public boolean isSearchable() {
        return BeanUtils.isEnabled(searchable);
    }

    public void setSearchable(Integer searchable) {
        this.searchable = searchable;
    }

    public Site setSearchable(boolean searchable) {
        if (getSearchable() != 0) setSearchable(BeanUtils.toInt(searchable));
        return this;
    }

    public boolean isChangeable() {
        return BeanUtils.isEnabled(changeable);
    }

    public void setChangeable(Integer changeable) {
        this.changeable = changeable;
    }

    public Site setChangeable(boolean changeable) {
        if (getChangeable() != 0) setChangeable(BeanUtils.toInt(changeable));
        return this;
    }

    public boolean isQuickSearch() {
        return BeanUtils.isEnabled(quickSearch);
    }

    public boolean isEmpty() {
        return getKey().isEmpty() && getName().isEmpty();
    }

    public Site fetchExt() {
        if (!UrlUtil.isHttp(getExt())) return this;
        String extend = OkHttp.string(getExt());
        if (!extend.isEmpty()) setExt(extend);
        return this;
    }

    public Site trans() {
        if (Trans.pass()) return this;
        setName(Trans.s2t(getName()));
        setCategories(getCategories().stream().map(Trans::s2t).toList());
        return this;
    }

    public Site sync(Site item) {
        if (item == null) return this;
        syncSearchable(item);
        syncChangeable(item);
        return this;
    }

    private void syncSearchable(Site item) {
        if (getSearchable() != 0) {
            setSearchable(Math.max(1, item.getSearchable()));
        }
    }

    private void syncChangeable(Site item) {
        if (getChangeable() != 0) {
            setChangeable(Math.max(1, item.getChangeable()));
        }
    }

    public Site recent() {
        BaseLoader.get().setRecent(getKey(), getApi(), getJar());
        return this;
    }

    public Spider spider() {
        return BaseLoader.get().getSpider(getKey(), getApi(), getExt(), getJar());
    }

    public void save() {
        if (DataCache.isReady()) DataCache.get().putSite(this);
        else AppDatabase.get().getSiteDao().insertOrUpdate(this);
    }

    public void saveAsync() {
        // Use final local copy to avoid implicit reference capture of 'this'
        final Site site = this;
        AsyncDB.execute(() -> AppDatabase.get().getSiteDao().insertOrUpdate(site));
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Site)) return false;

        Site it = (Site) obj;
        return Objects.equals(getKey(), it.getKey());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getKey());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(this.key);
        dest.writeString(this.name);
        dest.writeString(this.api);
        dest.writeString(this.ext);
        dest.writeString(this.jar);
        dest.writeString(this.click);
        dest.writeString(this.playUrl);
        dest.writeValue(this.type);
        dest.writeValue(this.hide);
        dest.writeValue(this.indexs);
        dest.writeValue(this.timeout);
        dest.writeValue(this.searchable);
        dest.writeValue(this.changeable);
        dest.writeValue(this.quickSearch);
        dest.writeStringList(this.categories);
        dest.writeParcelable(this.style, flags);
        dest.writeByte(this.activated ? (byte) 1 : (byte) 0);
        dest.writeString(this.header != null ? App.gson().toJson(this.header) : null);
    }

    public static final Creator<Site> CREATOR = new Creator<>() {
        @Override
        public Site createFromParcel(Parcel source) {
            return new Site(source);
        }

        @Override
        public Site[] newArray(int size) {
            return new Site[size];
        }
    };
}
