package com.wuming.android.tv.api.config;
import com.wuming.android.tv.setting.AppSettings;



import com.wuming.android.tv.api.Decoder;
import com.wuming.android.tv.api.LiveApi;
import com.wuming.android.tv.api.loader.BaseLoader;
import com.wuming.android.tv.api.parser.LiveParser;
import com.wuming.android.tv.bean.BeanUtils;
import com.wuming.android.tv.bean.Channel;
import com.wuming.android.tv.bean.Config;
import com.wuming.android.tv.bean.Group;
import com.wuming.android.tv.bean.Keep;
import com.wuming.android.tv.bean.Live;
import com.wuming.android.tv.bean.Rule;
import com.wuming.android.tv.db.AppDatabase;
import com.wuming.android.tv.utils.Task;
import com.wuming.android.tv.event.ConfigEvent;
import com.wuming.android.tv.impl.Callback;
import com.wuming.android.tv.library.UserLibrary;
import com.wuming.android.tv.utils.UrlUtil;
import com.wuming.android.tv.utils.SourceTracker;
import com.wuming.tv.core.source.SectionAdmission;
import com.github.catvod.utils.Json;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.wuming.android.tv.utils.AppLog;

public class LiveConfig extends BaseConfig {

    private static final String TAG = LiveConfig.class.getSimpleName();

    private volatile Live home;
    private volatile List<Live> lives;
    private volatile Map<String, Live> liveIndex;
    private volatile List<Rule> rules;
    private volatile List<String> ads;

    public static LiveConfig get() {
        return Loader.INSTANCE;
    }

    public static String getUrl() {
        return get().getConfig().getUrl();
    }

    public static String getDesc() {
        return get().getConfig().getDesc();
    }

    public static int getHomeIndex() {
        return get().getLives().indexOf(get().getHome());
    }

    public static boolean isOnly() {
        return get().getLives().size() == 1;
    }

    public static boolean isEmpty() {
        return get().getHome().isEmpty();
    }

    public static boolean hasUrl() {
        return BeanUtils.isNotEmpty(getUrl());
    }

    /** Returns only the already-resolved value and never performs database I/O. */
    public static boolean hasLoadedUrl() {
        Config config = get().config;
        return config != null && BeanUtils.isNotEmpty(config.getUrl());
    }

    public static void load(Config config, Callback callback) {
        get().clear().config(config).load(callback);
    }

    public LiveConfig init() {
        // Don't call Config.live() here - it does a DB query which must not run on main thread
        // The config will be fetched in load() which runs on a background thread
        return this;
    }

    public LiveConfig config(Config config) {
        this.config = config;
        if (config.isEmpty()) return this;
        this.sync = config.getUrl().equals(VodConfig.getUrl());
        return this;
    }

    public synchronized LiveConfig clear() {
        ads = null;
        home = null;
        lives = null;
        liveIndex = null;
        rules = null;
        RuleConfig.get().invalidate();
        return this;
    }

    @Override
    protected String getTag() {
        return TAG;
    }

    @Override
    protected Config defaultConfig() {
        return Config.live();
    }

    @Override
    protected void postEvent() {
        super.postEvent();
        ConfigEvent.live();
    }

    @Override
    protected void load(Config config) throws Throwable {
        long startTime = System.currentTimeMillis();
        String json;
        try {
            json = Decoder.getJson(UrlUtil.convert(config.getUrl()), TAG);
            config.json(json);
        } catch (Throwable e) {
            String cached = config.getJson();
            if (cached.isEmpty()) throw e;
            AppLog.w(TAG, "Network fetch failed; using cached config JSON");
            json = cached;
        }
        long pingTime = System.currentTimeMillis() - startTime;
        if (Json.isObj(json)) {
            JsonObject object = Json.parse(json).getAsJsonObject();
            if (!checkJson(config, object, LIVE)) parseConfig(config, object);
        } else {
            parseText(config, json);
        }
        // Track source usage
        SourceTracker.trackSourceUsage(config.getUrl(), config.getName(), "Live", pingTime);
    }

    @Override
    protected boolean isLoaded() {
        return !getLives().isEmpty() && !getHome().getGroups().isEmpty();
    }

    @Override
    public synchronized void ensureLoaded() {
        try {
            if (isLoaded()) return;
            super.ensureLoaded();
            LiveApi.parse(getHome());
            LiveApi.parseXml(getHome());
        } catch (Throwable e) {
            AppLog.e(TAG, e);
        }
    }

    public void load() {
        if (sync) return;
        load(new Callback());
    }

    private void parseText(Config config, String text) {
        Live live = new Live(UrlUtil.getName(config.getUrl()), config.getUrl()).sync();
        lives = new ArrayList<>(List.of(live));
        LiveParser.text(live, text);
        setHome(config, live, false);
    }

    private void parseConfig(Config config, JsonObject object) {
        initList(object);
        initLive(config, object);
    }

    public void parse(JsonObject object) {
        parseConfig(getConfig(), object);
    }

    private void initList(JsonObject object) {
        initCommonList(object);
    }

    private void initLive(Config config, JsonObject object) {
        String spider = Json.safeString(object, "spider");
        BaseLoader.get().parseJar(spider, false);
        SectionAdmission<Live> admission = ConfigAdmission.LIVES.admitJson(object, "lives", e -> Live.objectFrom(e, spider));
        ConfigAdmission.log(TAG, admission);
        setLives(new ArrayList<>(admission.getKept()));
        Map<String, Live> items = Live.findAll().stream().collect(Collectors.toMap(Live::getName, Function.identity()));
        getLives().forEach(live -> live.sync(items.get(live.getName())));
        setHome(config, getLives().isEmpty() ? new Live() : getLives().stream().filter(item -> item.getName().equals(config.getHome())).findFirst().orElse(getLives().get(0)), false);
    }

    public void setKeep(Channel channel) {
        if (home != null && !channel.getGroup().isHidden()) home.keep(channel).save();
    }

    public void applyKeepsToGroups(List<Group> items) {
        Set<String> key = UserLibrary.get().liveFavorites().stream()
                .map(Keep::getKey).collect(Collectors.toSet());
        items.stream().filter(group -> !group.isKeep())
                .flatMap(group -> group.getChannel().stream())
                .filter(channel -> key.contains(channel.getName()))
                .forEach(channel -> items.get(0).add(channel));
    }

    public int[] findKeepPosition(List<Group> items) {
        if (items.isEmpty()) return new int[]{0, 0};
        String[] splits = getHome().getKeep().split(AppDatabase.SYMBOL);
        if (splits.length < 3) return new int[]{Math.min(1, items.size() - 1), 0};
        for (int i = 0; i < items.size(); i++) {
            Group group = items.get(i);
            if (group.getName().equals(splits[0])) {
                int j = group.find(splits[1]);
                if (j != -1) {
                    group.getChannel().get(j).setIndex(splits[2]);
                    return new int[]{i, j};
                }
            }
        }
        return new int[]{Math.min(1, items.size() - 1), 0};
    }

    public int[] findByChannelNumber(String number, List<Group> items) {
        try {
            int num = Integer.parseInt(number);
            for (int i = 0; i < items.size(); i++) {
                int j = items.get(i).find(num);
                if (j != -1) return new int[]{i, j};
            }
        } catch (NumberFormatException ignored) {
        }
        return new int[]{-1, -1};
    }

    public List<Live> getLives() {
        return BeanUtils.emptyListIfNull(lives);
    }

    private void setLives(List<Live> lives) {
        Map<String, Live> index = new HashMap<>((int) (lives.size() / 0.75f) + 1);
        for (Live live : lives) index.put(live.getName(), live);
        this.liveIndex = Collections.unmodifiableMap(index);
        this.lives = lives;
    }

    public List<Rule> getRules() {
        return BeanUtils.emptyListIfNull(rules);
    }

    @Override
    protected void setRules(List<Rule> rules) {
        this.rules = rules;
        RuleConfig.get().invalidate();
    }

    public List<String> getAds() {
        return BeanUtils.emptyListIfNull(ads);
    }

    @Override
    protected void setAds(List<String> ads) {
        this.ads = ads;
        RuleConfig.get().invalidate();
    }

    public Live getHome() {
        return home == null ? new Live() : home;
    }

    public void setHome(Live home) {
        setHome(getConfig(), home, true);
    }

    public Live getLive(String key) {
        if (liveIndex != null) { Live l = liveIndex.get(key); return l != null ? l : new Live(); }
        return getLives().stream().filter(item -> item.getName().equals(key)).findFirst().orElseGet(Live::new);
    }

    private synchronized void setHome(Config config, Live live, boolean save) {
        home = live;
        home.setActivated(true);
        config.setHome(home.getName());
        // Save in background thread to avoid main thread database access crash — same pattern as VodConfig.setHome
        if (save) Task.execute(() -> config.save());
        getLives().forEach(item -> item.setActivated(home));
        if (!save && AppSettings.isBootLive()) ConfigEvent.boot();
    }

    private static class Loader {
        static volatile LiveConfig INSTANCE = new LiveConfig();
    }
}
