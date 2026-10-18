package com.wuming.android.tv.api.config;

import com.wuming.android.tv.App;
import com.wuming.android.tv.api.Decoder;
import com.wuming.android.tv.api.loader.BaseLoader;
import com.wuming.android.tv.bean.BeanUtils;
import com.wuming.android.tv.bean.Config;
import com.wuming.android.tv.bean.Parse;
import com.wuming.android.tv.bean.Rule;
import com.wuming.android.tv.bean.Site;
import com.wuming.android.tv.event.ConfigEvent;
import com.wuming.android.tv.event.RefreshEvent;
import com.wuming.android.tv.impl.Callback;
import com.wuming.android.tv.utils.AppLog;
import com.wuming.android.tv.utils.UrlUtil;
import com.wuming.android.tv.utils.SourceTracker;
import com.wuming.android.tv.utils.Task;
import com.wuming.tv.core.source.SectionAdmission;
import com.wuming.tv.core.source.TvBoxSourceIndex;
import com.github.catvod.bean.Doh;
import com.github.catvod.utils.Json;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class VodConfig extends BaseConfig {

    private static final String TAG = VodConfig.class.getSimpleName();
    public static final String OPEN_DRIVE_KEY = "builtin_opendrive";

    private volatile Site home;
    private volatile String wall;
    private volatile Parse parse;
    private volatile List<Doh> doh;
    private volatile List<Rule> rules;
    private volatile List<Site> sites;
    private volatile List<String> ads;
    private volatile List<String> flags;
    private volatile List<Parse> parses;
    private volatile Map<String, Site> siteIndex;
    private volatile Map<String, Parse> parseIndex;

    public static VodConfig get() {
        return Loader.INSTANCE;
    }

    public static int getCid() {
        return get().getConfig().getId();
    }

    public static String getUrl() {
        return get().getConfig().getUrl();
    }

    public static String getDesc() {
        return get().getConfig().getDesc();
    }

    public static int getHomeIndex() {
        return get().getSites().indexOf(get().getHome());
    }

    public static boolean hasParse() {
        return !get().getParses().isEmpty();
    }

    public static void load(Config config, Callback callback) {
        get().clear().config(config).load(callback);
    }

    public VodConfig init() {
        // Don't call Config.vod() here - it does a DB query which must not run on main thread
        // The config will be fetched in load() which runs on a background thread
        return this;
    }

    public VodConfig config(Config config) {
        this.config = config;
        return this;
    }

    public synchronized VodConfig clear() {
        ads = null;
        doh = null;
        home = null;
        wall = null;
        parse = null;
        sites = null;
        flags = null;
        rules = null;
        parses = null;
        siteIndex = null;
        parseIndex = null;
        // Provider loaders are process-scoped. Clearing them while a SiteApi task
        // is in flight can invalidate native provider state during activity or
        // configuration recreation. JarLoader keys spiders by their full config,
        // so changed provider settings still create a fresh instance.
        RuleConfig.get().invalidate();
        com.wuming.android.tv.api.SiteApi.clearDetailCache();
        return this;
    }

    @Override
    protected String getTag() {
        return TAG;
    }

    @Override
    protected Config defaultConfig() {
        return Config.vod();
    }

    @Override
    protected void postEvent() {
        super.postEvent();
        ConfigEvent.vod();
        // Refresh history when config is loaded to ensure proper filtering
        RefreshEvent.history();
    }

    @Override
    protected void load(Config config) throws Throwable {
        long startTime = System.currentTimeMillis();
        String json;
        try {
            json = Decoder.getJsonObject(UrlUtil.convert(config.getUrl()), TAG);
            config.json(json);
        } catch (Throwable e) {
            String cached = config.getJson();
            if (cached.isEmpty()) throw e;
            AppLog.w(TAG, "Network fetch failed; using cached config JSON");
            json = cached;
        }
        long pingTime = System.currentTimeMillis() - startTime;
        JsonObject object = Json.parse(json).getAsJsonObject();
        if (!checkJson(config, object, VOD)) parseConfig(config, object);
        // Track source usage
        SourceTracker.trackSourceUsage(config.getUrl(), config.getName(), "VOD", pingTime);
    }

    @Override
    protected boolean isLoaded() {
        return !getSites().isEmpty();
    }

    private void parseConfig(Config config, JsonObject object) {
        initList(object);
        initLive(config, object);
        initWall(config, object);
        initSite(config, object);
        initParse(config, object);
        config.setLogo(Json.safeString(object, "logo"));
        config.setNotice(Json.safeString(object, "notice"));
        config.setDanmaku(Json.safeString(object, "danmaku"));
    }

    private void initList(JsonObject object) {
        initCommonList(object);
        setDoh(Doh.arrayFrom(fetchArray(object, "doh")));
        setFlags(Json.safeListString(object, "flags"));
    }

    private void initLive(Config config, JsonObject object) {
        if (Json.isEmpty(object, "lives")) return;
        Config temp = Config.find(config, LIVE).save();
        boolean sync = LiveConfig.get().needSync(config.getUrl());
        if (sync) LiveConfig.get().config(temp.update()).parse(object);
    }

    private void initWall(Config config, JsonObject object) {
        if (Json.isEmpty(object, "wallpaper")) return;
        this.wall = Json.safeString(object, "wallpaper");
        Config temp = Config.find(wall, config.getName(), WALL).save();
        boolean sync = WallConfig.get().needSync(wall);
        if (sync) WallConfig.get().config(temp.update());
    }

    private void initSite(Config config, JsonObject object) {
        // The spider jar reference is parsed through the shared TvBoxConfigDecoder so
        // the Android app and the desktop client read the config with identical
        // semantics; site entities themselves stay Gson-bound (Room/ExtAdapter).
        String spider = com.wuming.tv.core.source.config.TvBoxConfigDecoder.decode(object).getSpider();
        final String spiderJar = spider != null ? spider : "";
        BaseLoader.get().parseJar(spiderJar, true);
        SectionAdmission<Site> admission = ConfigAdmission.SITES.admitJson(object, "sites", e -> Site.objectFrom(e, spiderJar));
        ConfigAdmission.log(TAG, admission);
        List<Site> configured = new ArrayList<>(admission.getKept());
        String packageId = Json.safeString(object, "wumingPackageId");
        if (packageId.matches("[a-f0-9]{32}")) {
            configured.forEach(site -> site.setKey(com.wuming.tv.core.source.packages.PackageSourceIdentity.scoped(packageId, site.getKey())));
        }
        setSites(withBuiltinOpenDrive(configured));
        Map<String, Site> items = TvBoxSourceIndex.indexByKey(Site.findAll(), Site::getKey);
        getSites().forEach(site -> site.sync(items.get(site.getKey())));
        Site selectedHome = SiteSourceAdapter.catalog().selectPreferred(getSites(), config.getHome());
        if (selectedHome == null) selectedHome = new Site();
        setHome(config, selectedHome, false);
    }

    static List<Site> withBuiltinOpenDrive(List<Site> configured) {
        return SiteSourceAdapter.catalog().withRequiredSource(configured,
                Site.builtinSpider(OPEN_DRIVE_KEY, "OpenDrive (Built-in)", "builtin_opendrive"));
    }

    private void initParse(Config config, JsonObject object) {
        setParses(Json.safeListElement(object, "parses").stream().map(Parse::objectFrom).distinct().collect(Collectors.toCollection(ArrayList::new)));
        setParse(config, getParses().isEmpty() ? new Parse() : getParses().stream().filter(item -> item.getName().equals(config.getParse())).findFirst().orElseGet(() -> getParses().get(0)), false);
    }

    public List<Site> getSites() {
        return BeanUtils.emptyListIfNull(sites);
    }

    private void setSites(List<Site> sites) {
        Map<String, Site> index = new HashMap<>(SiteSourceAdapter.catalog().index(sites));
        this.siteIndex = Collections.unmodifiableMap(index);
        this.sites = sites;
    }

    public List<Parse> getParses() {
        return BeanUtils.emptyListIfNull(parses);
    }

    private void setParses(List<Parse> parses) {
        List<Parse> copy = new ArrayList<>(parses);
        if (!copy.isEmpty()) copy.add(0, Parse.god());
        Map<String, Parse> index = new LinkedHashMap<>(TvBoxSourceIndex.indexByName(copy, Parse::getName));
        this.parseIndex = Collections.unmodifiableMap(index);
        this.parses = copy;
    }

    public List<Doh> getDoh() {
        List<Doh> defaults = Doh.get(App.get());
        if (doh == null || doh.isEmpty()) return defaults;
        // Build merged list: custom entries override defaults with the same key
        Map<String, Doh> merged = new LinkedHashMap<>();
        for (Doh item : defaults) merged.put(item.getName(), item);
        for (Doh item : doh) merged.put(item.getName(), item);
        return new ArrayList<>(merged.values());
    }

    private void setDoh(List<Doh> doh) {
        this.doh = doh;
    }

    public List<Rule> getRules() {
        return BeanUtils.emptyListIfNull(rules);
    }

    @Override
    protected void setRules(List<Rule> rules) {
        this.rules = rules;
        RuleConfig.get().invalidate();
    }

    public List<Parse> getParses(int type) {
        return getParses().stream().filter(item -> item.getType() == type).toList();
    }

    public List<Parse> getParses(int type, String flag) {
        List<Parse> items = getParses(type);
        List<Parse> filter = items.stream().filter(item -> item.getExt().getFlag().contains(flag)).toList();
        return filter.isEmpty() ? items : filter;
    }

    public List<String> getFlags() {
        return BeanUtils.emptyListIfNull(flags);
    }

    private void setFlags(List<String> flags) {
        this.flags = flags;
    }

    public List<String> getAds() {
        return BeanUtils.emptyListIfNull(ads);
    }

    @Override
    protected void setAds(List<String> ads) {
        this.ads = ads;
        RuleConfig.get().invalidate();
    }

    private static final Site EMPTY_SITE = new Site();
    private static final Parse EMPTY_PARSE = new Parse();

    public Parse getParse() {
        return parse == null ? EMPTY_PARSE : parse;
    }

    public void setParse(Parse parse) {
        setParse(getConfig(), parse, true);
    }

    public Site getHome() {
        return home == null ? EMPTY_SITE : home;
    }

    public void setHome(Site site) {
        setHome(getConfig(), site, true);
        RefreshEvent.home();
    }

    public String getWall() {
        return BeanUtils.emptyIfNull(wall);
    }

    public Parse getParse(String name) {
        if (parseIndex != null) { Parse p = parseIndex.get(name); return p != null ? p : new Parse(); }
        return getParses().stream().filter(item -> item.getName().equals(name)).findFirst().orElseGet(Parse::new);
    }

    public Site getSite(String key) {
        if (siteIndex != null) { Site s = siteIndex.get(key); if (s != null) return s; }
        // Provider callbacks and the push handler retain original keys within the active package.
        return getSites().stream().filter(item -> item.getKey().equals(key)
                || com.wuming.tv.core.source.packages.PackageSourceIdentity.original(item.getKey()).equals(key))
                .findFirst().orElseGet(Site::new);
    }

    private synchronized void setParse(Config config, Parse parse, boolean save) {
        this.parse = parse;
        this.parse.setActivated(true);
        config.setParse(parse.getName());
        getParses().forEach(item -> item.setActivated(parse));
        // Save in background thread to avoid main thread database access crash
        if (save) Task.execute(() -> config.save());
    }

    private synchronized void setHome(Config config, Site site, boolean save) {
        home = site;
        home.setActivated(true);
        config.setHome(home.getKey());
        // Save in background thread to avoid main thread database access crash
        if (save) Task.execute(() -> config.save());
        getSites().forEach(item -> item.setActivated(home));
    }

    private static class Loader {
        static volatile VodConfig INSTANCE = new VodConfig();
    }
}