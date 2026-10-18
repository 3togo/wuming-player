package com.wuming.android.tv.bean;



import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

import com.wuming.android.tv.App;
import com.wuming.android.tv.Constant;
import com.wuming.android.tv.R;
import com.wuming.android.tv.api.loader.BaseLoader;
import com.wuming.android.tv.db.AppDatabase;
import com.wuming.android.tv.db.AsyncDB;
import com.wuming.android.tv.db.DataCache;
import com.wuming.android.tv.gson.ExtAdapter;
import com.wuming.android.tv.gson.HeaderAdapter;
import com.wuming.android.tv.utils.UrlUtil;
import com.github.catvod.crawler.Spider;
import com.github.catvod.utils.Trans;
import com.google.common.net.HttpHeaders;
import com.google.gson.JsonElement;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Entity
public class Live {

    @NonNull
    @PrimaryKey
    @SerializedName("name")
    private String name;

    @Ignore
    @SerializedName("url")
    private String url;

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
    @SerializedName("logo")
    private String logo;

    @Ignore
    @SerializedName("epg")
    private String epg;

    @Ignore
    @SerializedName("ua")
    private String ua;

    @Ignore
    @SerializedName("origin")
    private String origin;

    @Ignore
    @SerializedName("referer")
    private String referer;

    @Ignore
    @SerializedName("timeZone")
    private String timeZone;

    @SerializedName("keep")
    private String keep;

    @Ignore
    @SerializedName("timeout")
    private Integer timeout;

    @Ignore
    @SerializedName("header")
    @JsonAdapter(HeaderAdapter.class)
    private Map<String, String> header;

    @Ignore
    @SerializedName("catchup")
    private Catchup catchup;

    @Ignore
    @SerializedName("groups")
    private List<Group> groups;

    @SerializedName("boot")
    private boolean boot;

    @SerializedName("pass")
    private boolean pass;

    @Ignore
    private boolean activated;

    @Ignore
    private int width;

    public Live() {
    }

    public Live(@NonNull String name, String url) {
        this.name = name;
        this.url = url;
    }

    public static Live objectFrom(JsonElement element, String spider) {
        try {
            Live live = App.gson().fromJson(element, Live.class);
            if (live.getJar().isEmpty()) live.setJar(spider);
            live.setApi(UrlUtil.convert(live.getApi()));
            live.setExt(UrlUtil.convert(live.getExt()));
            return live.trans();
        } catch (Exception e) {
            return new Live();
        }
    }

    public static List<Live> findAll() {
        if (DataCache.isReady()) return DataCache.get().getLives();
        return AppDatabase.get().getLiveDao().findAll();
    }

    public static Live find(String name) {
        if (DataCache.isReady()) {
            for (Live l : DataCache.get().getLives()) {
                if (name.equals(l.getName())) return l;
            }
            return null;
        }
        return AppDatabase.get().getLiveDao().find(name);
    }

    // Async versions for UI thread safety

    public static void findAllAsync(java.util.function.Consumer<List<Live>> callback) {
        AsyncDB.query(Live::findAll, callback);
    }

    public static void findAllAsyncSafe(WeakReference<java.util.function.Consumer<List<Live>>> callbackRef) {
        AsyncDB.query(Live::findAll, result -> {
            java.util.function.Consumer<List<Live>> callback = callbackRef.get();
            if (callback != null) {
                callback.accept(result);
            }
        });
    }

    public static void findAsync(String name, java.util.function.Consumer<Live> callback) {
        AsyncDB.query(() -> find(name), callback);
    }

    public String getName() {
        return BeanUtils.emptyIfNull(name);
    }

    public void setName(@NonNull String name) {
        this.name = name;
    }

    public String getUrl() {
        return BeanUtils.emptyIfNull(url);
    }

    public void setUrl(String url) {
        this.url = url;
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

    public String getLogo() {
        return BeanUtils.emptyIfNull(logo);
    }

    public String getEpg() {
        return BeanUtils.emptyIfNull(epg);
    }

    public void setEpg(String epg) {
        this.epg = epg;
    }

    public String getUa() {
        return BeanUtils.emptyIfNull(ua);
    }

    public String getOrigin() {
        return BeanUtils.emptyIfNull(origin);
    }

    public String getReferer() {
        return BeanUtils.emptyIfNull(referer);
    }

    public String getTimeZone() {
        return BeanUtils.emptyIfNull(timeZone);
    }

    public String getKeep() {
        return BeanUtils.emptyIfNull(keep);
    }

    public void setKeep(String keep) {
        this.keep = keep;
    }

    public long getTimeout() {
        return BeanUtils.toMillisTimeout(timeout, Constant.TIMEOUT_PLAY);
    }

    public Map<String, String> getHeader() {
        return BeanUtils.emptyMapIfNull(header);
    }

    public Catchup getCatchup() {
        return catchup == null ? Catchup.empty() : catchup;
    }

    public List<Group> getGroups() {
        return groups = groups == null ? new ArrayList<>() : groups;
    }

    public boolean isBoot() {
        return boot;
    }

    public void setBoot(boolean boot) {
        this.boot = boot;
    }

    public boolean isPass() {
        return pass;
    }

    public void setPass(boolean pass) {
        this.pass = pass;
    }

    public boolean isActivated() {
        return activated;
    }

    public void setActivated(boolean activated) {
        this.activated = activated;
    }

    public void setActivated(Live item) {
        this.activated = item.equals(this);
    }

    public int getWidth() {
        return width;
    }

    public void setWidth(int width) {
        this.width = width;
    }

    public String getEpgApi() {
        for (String url : getEpg().split(",")) if (url.contains("{")) return url;
        return getEpg();
    }

    public List<String> getEpgXml() {
        List<String> items = new ArrayList<>();
        for (String epg : getEpg().split(",")) if (!epg.contains("{") && (epg.contains("xml") || epg.contains("gz"))) items.add(epg);
        return items;
    }

    public boolean isEmpty() {
        return getName().isEmpty();
    }

    public Group find(Group item) {
        for (Group group : getGroups()) if (group.getName().equals(item.getName())) return group;
        getGroups().add(item);
        return item;
    }

    public int getBootIcon() {
        return isBoot() ? R.drawable.ic_live_boot : R.drawable.ic_live_block;
    }

    public int getPassIcon() {
        return isPass() ? R.drawable.ic_live_block : R.drawable.ic_live_pass;
    }

    public Live boot(boolean boot) {
        setBoot(boot);
        return this;
    }

    public Live pass(boolean pass) {
        getGroups().clear();
        setPass(pass);
        return this;
    }

    public Live keep(Channel channel) {
        setKeep(channel.getGroup().getName() + AppDatabase.SYMBOL + channel.getName() + AppDatabase.SYMBOL + channel.getCurrent());
        return this;
    }

    public Live trans() {
        if (Trans.pass()) return this;
        this.name = Trans.s2t(name);
        return this;
    }

    public Live sync() {
        sync(find(getName()));
        return this;
    }

    public Live sync(Live item) {
        if (item == null) return this;
        setBoot(item.isBoot());
        setPass(item.isPass());
        setKeep(item.getKeep());
        return this;
    }

    public Live recent() {
        BaseLoader.get().setRecent(getName(), getApi(), getJar());
        return this;
    }

    public Spider spider() {
        return BaseLoader.get().getSpider(getName(), getApi(), getExt(), getJar());
    }

    public Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>(getHeader());
        if (!getUa().isEmpty()) headers.put(HttpHeaders.USER_AGENT, getUa());
        if (!getOrigin().isEmpty()) headers.put(HttpHeaders.ORIGIN, getOrigin());
        if (!getReferer().isEmpty()) headers.put(HttpHeaders.REFERER, getReferer());
        return headers;
    }

    public void save() {
        if (DataCache.isReady()) DataCache.get().putLive(this);
        else AppDatabase.get().getLiveDao().insertOrUpdate(this);
    }

    public void saveAsync() {
        Live self = this;
        AsyncDB.execute(() -> AppDatabase.get().getLiveDao().insertOrUpdate(self));
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Live)) return false;

        Live it = (Live) obj;
        return getName().equals(it.getName());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getName());
    }
}