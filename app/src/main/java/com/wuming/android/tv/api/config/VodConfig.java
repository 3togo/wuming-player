package com.wuming.android.tv.api.config;

import com.wuming.android.tv.App;
import com.wuming.android.tv.api.loader.BaseLoader;
import com.wuming.android.tv.bean.BeanUtils;
import com.wuming.android.tv.bean.Config;
import com.wuming.android.tv.bean.Parse;
import com.wuming.android.tv.bean.Rule;
import com.wuming.android.tv.bean.Site;
import com.wuming.android.tv.event.ConfigEvent;
import com.wuming.android.tv.event.RefreshEvent;
import com.wuming.android.tv.impl.Callback;
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
        get().requestLoad(config, callback);
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
    protected PreparedLoad prepare(Config requested, int request) throws Throwable {
        SourceDocument source = readSource(requested, VOD, true, request);
        Config config = source.config();
        JsonObject object = source.json();
        String tag = requestTag(request);
        Runnable common = prepareCommonList(object, tag);
        List<Doh> nextDoh = Doh.arrayFrom(fetchArray(object, "doh", tag));
        List<String> nextFlags = Json.safeListString(object, "flags");
        String nextWall = Json.safeString(object, "wallpaper");

        String parsedJar = com.wuming.tv.core.source.config.TvBoxConfigDecoder.decode(object).getSpider();
        String spiderJar = parsedJar == null ? "" : parsedJar;
        SectionAdmission<Site> admission = ConfigAdmission.SITES.admitJson(object, "sites", e -> Site.objectFrom(e, spiderJar));
        ConfigAdmission.log(TAG, admission);
        List<Site> configured = new ArrayList<>(admission.getKept());
        String packageId = Json.safeString(object, "wumingPackageId");
        if (packageId.matches("[a-f0-9]{32}")) {
            configured.forEach(site -> site.setKey(com.wuming.tv.core.source.packages.PackageSourceIdentity.scoped(packageId, site.getKey())));
        }
        List<Site> nextSites = withBuiltinOpenDrive(configured);
        Map<String, Site> savedSites = TvBoxSourceIndex.indexByKey(Site.findAll(), Site::getKey);
        nextSites.forEach(site -> site.sync(savedSites.get(site.getKey())));
        Site selectedHome = SiteSourceAdapter.catalog().selectPreferred(nextSites, config.getHome());
        List<Parse> nextParses = Json.safeListElement(object, "parses").stream()
                .map(Parse::objectFrom).distinct().toList();
        String logo = Json.safeString(object, "logo");
        String notice = Json.safeString(object, "notice");
        String danmaku = Json.safeString(object, "danmaku");

        LiveConfig live = LiveConfig.get();
        int liveRequest = live.currentTaskId();
        PreparedLoad embeddedLive = !Json.isEmpty(object, "lives") && live.acceptsEmbedded(liveRequest, config.getUrl())
                ? live.prepareEmbedded(Config.prepareSource(config.getUrl(), config.getName(), LIVE), object, tag) : null;
        WallConfig wallpaper = WallConfig.get();
        int wallRequest = wallpaper.currentTaskId();
        Config embeddedWall = !BeanUtils.isEmpty(nextWall) && wallpaper.acceptsEmbedded(wallRequest, nextWall)
                ? Config.prepareSource(nextWall, config.getName(), WALL) : null;

        return new PreparedLoad(config, () -> {
            common.run();
            setDoh(nextDoh);
            setFlags(nextFlags);
            wall = nextWall;
            BaseLoader.get().selectParserJar(spiderJar);
            setSites(nextSites);
            setHome(config, selectedHome == null ? new Site() : selectedHome, false);
            setParses(nextParses);
            setParse(config, getParses().stream().filter(item -> item.getName().equals(config.getParse()))
                    .findFirst().orElseGet(() -> getParses().isEmpty() ? new Parse() : getParses().get(0)), false);
            config.json(source.text());
            config.setLogo(logo);
            config.setNotice(notice);
            config.setDanmaku(danmaku);
            if (embeddedLive != null) live.installEmbedded(liveRequest, embeddedLive);
            if (embeddedWall != null) wallpaper.installEmbedded(wallRequest, embeddedWall);
            com.wuming.android.tv.api.SiteApi.clearDetailCache();
            SourceTracker.trackSourceUsage(config.getUrl(), config.getName(), "VOD", source.fetchMillis());
        }, () -> {}, () -> {
            BaseLoader.get().parseJar(spiderJar, false);
            if (embeddedLive != null) embeddedLive.preload().run();
        });
    }

    @Override
    protected boolean isLoaded() {
        return !getSites().isEmpty();
    }

    static List<Site> withBuiltinOpenDrive(List<Site> configured) {
        return SiteSourceAdapter.catalog().withRequiredSource(configured,
                Site.builtinSpider(OPEN_DRIVE_KEY, "OpenDrive (Built-in)", "builtin_opendrive"));
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