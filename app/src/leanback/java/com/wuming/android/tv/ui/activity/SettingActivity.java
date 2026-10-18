package com.wuming.android.tv.ui.activity;
import com.wuming.android.tv.setting.DisplaySettings;
import com.wuming.android.tv.setting.AppSettings;
import com.wuming.android.tv.setting.UiTheme;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.viewbinding.ViewBinding;

import com.wuming.android.tv.BuildConfig;
import com.wuming.android.tv.R;
import com.wuming.android.tv.Updater;
import com.wuming.android.tv.api.config.LiveConfig;
import com.wuming.android.tv.api.config.VodConfig;
import com.wuming.android.tv.api.config.WallConfig;
import com.wuming.android.tv.bean.Config;
import com.wuming.android.tv.bean.Live;
import com.wuming.android.tv.bean.Site;
import com.wuming.android.tv.databinding.ActivitySettingBinding;
import com.wuming.android.tv.event.ConfigEvent;
import com.wuming.android.tv.event.RefreshEvent;
import com.wuming.android.tv.impl.Callback;
import com.wuming.android.tv.impl.ConfigCallback;
import com.wuming.android.tv.impl.DohCallback;
import com.wuming.android.tv.impl.LiveCallback;
import com.wuming.android.tv.impl.SiteCallback;
import com.wuming.android.tv.ui.SettingsOperations;
import com.wuming.android.tv.ui.base.BaseActivity;
import com.wuming.android.tv.ui.dialog.LeanbackConfigDialog;
import com.wuming.android.tv.ui.dialog.DohDialog;
import com.wuming.android.tv.ui.dialog.HistoryDialog;
import com.wuming.android.tv.ui.dialog.LiveDialog;
import com.wuming.android.tv.ui.dialog.BackupDialog;
import com.wuming.android.tv.ui.dialog.RestoreDialog;
import com.wuming.android.tv.ui.dialog.LeanbackSiteDialog;
import com.wuming.android.tv.ui.dialog.SiteRankingDialog;
import com.wuming.android.tv.ui.dialog.DepotDialog;
import com.wuming.android.tv.utils.FileChooser;
import com.wuming.android.tv.utils.FileUtil;
import com.wuming.android.tv.utils.Notify;
import com.wuming.android.tv.utils.PermissionUtil;
import com.wuming.android.tv.utils.ResUtil;
import com.wuming.android.tv.utils.DepotUtil;
import com.wuming.android.tv.utils.GitHubProxyList;
import com.github.catvod.bean.Doh;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.net.URL;
import java.lang.ref.WeakReference;

public class SettingActivity extends BaseActivity implements ConfigCallback, SiteCallback, LiveCallback, DohCallback {

    private ActivitySettingBinding mBinding;
    private String[] size;
    private int type;
    private static final String THEME_FOCUS_EXTRA = "theme_focus";

    /** Debug/testing intent action (verification builds only) to jump straight to the Depot dialog. */
    private static final String OPEN_DEPOT_ACTION = BuildConfig.APPLICATION_ID + ".action.OPEN_DEPOT";
    /** Optional extra: a VOD URL to seed as the only depot source before opening the dialog. */
    private static final String EXTRA_DEPOT_URL = BuildConfig.APPLICATION_ID + ".extra.DEPOT_URL";

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SettingActivity.class));
    }

    private boolean depotOpened = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (OPEN_DEPOT_ACTION.equals(getIntent().getAction())) {
            // Optional deterministic seed so a script/test can exercise the "Use" flow.
            String seed = getIntent().getStringExtra(EXTRA_DEPOT_URL);
            if (seed != null) {
                try {
                    com.wuming.android.tv.utils.DepotManager.writeTestDepot(seed);
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Open the Depot dialog from onResume (window attached) rather than onCreate, so it is
        // reliable under both a normal launch and an instrumentation ActivityScenario. One-shot.
        if (OPEN_DEPOT_ACTION.equals(getIntent().getAction()) && !depotOpened) {
            depotOpened = true;
            DepotDialog.create(this, this::addDepotConfig).show();
        }
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySettingBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        int themeFocus = getIntent().getIntExtra(THEME_FOCUS_EXTRA, View.NO_ID);
        getIntent().removeExtra(THEME_FOCUS_EXTRA);
        View initialFocus = themeFocus == View.NO_ID ? mBinding.vod : findViewById(themeFocus);
        if (initialFocus != null) initialFocus.requestFocus();
        mBinding.vodUrl.setText(VodConfig.getDesc());
        mBinding.liveUrl.setText(LiveConfig.getDesc());
        mBinding.wallUrl.setText(WallConfig.getDesc());
        mBinding.versionText.setText(BuildConfig.VERSION_NAME);

        setDepotText();
        setCacheText();
        setOtherText();
    }

    private void setDepotText() {
        mBinding.depotText.setText(DepotUtil.getDepotText(this));
    }

    private void setOtherText() {
        mBinding.dohText.setText(SettingsOperations.getDohNames()[SettingsOperations.getDohIndex()]);
        SettingsOperations.setToggleState(mBinding.incognitoText, AppSettings.isIncognito());
        SettingsOperations.setToggleState(mBinding.multiSiteSearchText, DisplaySettings.isGridSearchMode());
        mBinding.siteListLayoutText.setText(ResUtil.getStringArray(R.array.select_site_list_layout)[DisplaySettings.getSiteListLayout()]);
        mBinding.resultLayoutText.setText(ResUtil.getStringArray(R.array.select_result_layout)[DisplaySettings.getSearchView()]);
        mBinding.themeText.setText(ResUtil.getStringArray(R.array.select_theme)[DisplaySettings.getTheme()]);
        mBinding.colorThemeText.setText(ResUtil.getStringArray(R.array.select_color_theme)[DisplaySettings.getColorTheme()]);
        mBinding.liveScaleText.setText(ResUtil.getStringArray(R.array.select_scale)[DisplaySettings.getLiveScale()]);
        SettingsOperations.setToggleState(mBinding.updateText, AppSettings.getUpdate());
        mBinding.restartModeText.setText(getRestartModeText());
        SettingsOperations.setLinkRouteText(mBinding.linkRouteText);
        mBinding.sizeText.setText((size = ResUtil.getStringArray(R.array.select_size))[DisplaySettings.getSize()]);
        mBinding.githubProxyText.setText(SettingsOperations.getGithubProxyName());
    }

    private String getRestartModeText() {
        int mode = AppSettings.getRestartMode();
        String[] modes = {
            getString(R.string.restart_mode_0),
            getString(R.string.restart_mode_1),
            getString(R.string.restart_mode_2),
            getString(R.string.restart_mode_3),
            getString(R.string.restart_mode_4)
        };
        return modes[mode];
    }

    private void setCacheText() {
        SettingsOperations.loadCacheSize(this, mBinding.cacheText);
    }

    @Override
    protected void initEvent() {
        mBinding.vod.setOnClickListener(this::onVod);
        mBinding.doh.setOnClickListener(this::setDoh);
        mBinding.githubProxy.setOnClickListener(this::setGithubProxy);
        mBinding.githubProxySpeed.setOnClickListener(this::onGithubProxySpeed);
        mBinding.live.setOnClickListener(this::onLive);
        mBinding.wall.setOnClickListener(this::onWall);
        mBinding.size.setOnClickListener(this::setSize);
        mBinding.cache.setOnClickListener(this::onCache);
        mBinding.dlnaPairing.setOnClickListener(view -> DlnaPairingActivity.start(this));
        mBinding.networkInformation.setOnClickListener(view -> NetworkInformationActivity.start(this));
        mBinding.remoteTest.setOnClickListener(view -> startActivity(new Intent(this, RemoteTestActivity.class)));
        com.wuming.android.tv.ui.HardwareCapabilitySettings.bind(mBinding.videoCapabilities);
        mBinding.cloudStorage.setOnClickListener(view -> CloudStorageActivity.start(this));
        mBinding.linkRoute.setOnClickListener(this::setLinkRoute);
        mBinding.backup.setOnClickListener(this::onBackup);
        mBinding.player.setOnClickListener(this::onPlayer);
        mBinding.desktopRelay.setOnClickListener(view -> com.wuming.android.tv.player.relay.DesktopRelayDialog.show(this));
        mBinding.restore.setOnClickListener(this::onRestore);
        mBinding.version.setOnClickListener(this::onVersion);
        mBinding.vod.setOnLongClickListener(this::onVodEdit);
        mBinding.vodHome.setOnClickListener(this::onVodHome);
        mBinding.live.setOnLongClickListener(this::onLiveEdit);
        mBinding.liveHome.setOnClickListener(this::onLiveHome);
        mBinding.wall.setOnLongClickListener(this::onWallEdit);
        mBinding.incognito.setOnClickListener(this::setIncognito);
        mBinding.multiSiteSearch.setOnClickListener(this::setMultiSiteSearch);
        mBinding.siteListLayout.setOnClickListener(this::setSiteListLayout);
        mBinding.resultLayout.setOnClickListener(this::setResultLayout);
        mBinding.theme.setOnClickListener(this::setTheme);
        mBinding.colorTheme.setOnClickListener(this::setColorTheme);
        mBinding.liveScale.setOnClickListener(this::setLiveScale);
        mBinding.update.setOnClickListener(this::setUpdate);
        mBinding.restartMode.setOnClickListener(this::setRestartMode);
        mBinding.reloadQuit.setOnClickListener(this::onReloadQuit);
        mBinding.vodHistory.setOnClickListener(this::onVodHistory);
        mBinding.vodDefault.setOnClickListener(this::onVodDefault);
        mBinding.vodRanking.setOnClickListener(this::onVodRanking);
        mBinding.liveHistory.setOnClickListener(this::onLiveHistory);
        mBinding.liveDefault.setOnClickListener(this::onLiveDefault);
        mBinding.wallDefault.setOnClickListener(this::setWallDefault);
        mBinding.wallHistory.setOnClickListener(this::onWallHistory);
        mBinding.wallRefresh.setOnClickListener(this::setWallRefresh);
        mBinding.depot.setOnClickListener(this::onDepot);
        mBinding.depotUpdate.setOnClickListener(this::onDepotUpdate);
        mBinding.depotValidate.setOnClickListener(this::onDepotValidate);
    }

    private void onDepot(View view) {
        DepotDialog.create(this, this::addDepotConfig).show();
    }

    private void addDepotConfig(android.content.Context context, String url, int type, String name, boolean editUrl) {
        if (editUrl) LeanbackConfigDialog.create(this).launcher(launcher).type(type).url(url).unlockedUrl().show();
        else setConfig(Config.find(url, name, type));
    }

    private void onDepotUpdate(View view) {
        SettingsOperations.updateDepot(this, this, mBinding.depotText);
    }

    private void onDepotValidate(View view) {
        SettingsOperations.validateDepot(this, this, mBinding.depotText);
    }

    @Override
    public void setConfig(Config config) {
        if (config.getUrl().startsWith("file")) {
            PermissionUtil.requestFile(this, allGranted -> load(config));
        } else {
            load(config);
        }
    }

    private void load(Config config) {
        switch (config.getType()) {
            case 0:
                VodConfig.load(config, getCallback());
                break;
            case 1:
                LiveConfig.load(config, getCallback());
                break;
            case 2:
                DisplaySettings.putWall(0);
                WallConfig.load(config, getCallback());
                break;
        }
    }

    private Callback getCallback() {
        return lifecycleCallback(new Callback() {
            @Override
            public void start() {
                Notify.progress(getActivity());
            }

            @Override
            public void success() {
                Notify.dismiss();
                setCacheText();
            }

            @Override
            public void error(String msg) {
                Notify.dismiss();
                Notify.show(msg);
            }
        });
    }

    @Override
    public void setSite(Site item) {
        VodConfig.get().setHome(item);
    }

    @Override
    public void setLive(Live item) {
        LiveConfig.get().setHome(item);
    }

    private void onVod(View view) {
        LeanbackConfigDialog.create(this).launcher(launcher).type(type = 0).show();
    }

    private void onLive(View view) {
        LeanbackConfigDialog.create(this).launcher(launcher).type(type = 1).show();
    }

    private void onWall(View view) {
        LeanbackConfigDialog.create(this).launcher(launcher).type(type = 2).show();
    }

    private boolean onVodEdit(View view) {
        LeanbackConfigDialog.create(this).launcher(launcher).type(type = 0).edit().show();
        return true;
    }

    private boolean onLiveEdit(View view) {
        LeanbackConfigDialog.create(this).launcher(launcher).type(type = 1).edit().show();
        return true;
    }

    private boolean onWallEdit(View view) {
        LeanbackConfigDialog.create(this).launcher(launcher).type(type = 2).edit().show();
        return true;
    }

    private void onVodHome(View view) {
        LeanbackSiteDialog.create(this).action().show();
    }

    private void onLiveHome(View view) {
        LiveDialog.create(this).action().show();
    }

    private void onVodHistory(View view) {
        HistoryDialog.create(this).type(type = 0).show();
    }

    private void onVodDefault(View view) {
        Config.defaultsForce();
        Notify.show(R.string.setting_default);
    }

    private void onVodRanking(View view) {
        SiteRankingDialog.create().show(this);
    }

    private void onLiveHistory(View view) {
        HistoryDialog.create(this).type(type = 1).show();
    }

    private void onLiveDefault(View view) {
        Config.defaultsForce();
        Notify.show(R.string.setting_default);
    }

    private void onPlayer(View view) {
        SettingPlayerActivity.start(this);
    }

    private void onVersion(View view) {
        Updater.create().force().start(this);
    }

    private void setWallDefault(View view) {
        DisplaySettings.putWall(DisplaySettings.getWall() == 4 ? 1 : DisplaySettings.getWall() + 1);
        DisplaySettings.putWallType(0);
        ConfigEvent.wall();
    }

    private void setWallRefresh(View view) {
        DisplaySettings.putWall(0);
        WallConfig.get().load(getCallback());
    }

    private void onWallHistory(View view) {
        HistoryDialog.create(this).type(type = 2).show();
    }

    private void setIncognito(View view) {
        AppSettings.putIncognito(!AppSettings.isIncognito());
        SettingsOperations.setToggleState(mBinding.incognitoText, AppSettings.isIncognito());
    }

    private void setMultiSiteSearch(View view) {
        DisplaySettings.putGridSearchMode(!DisplaySettings.isGridSearchMode());
        SettingsOperations.setToggleState(mBinding.multiSiteSearchText, DisplaySettings.isGridSearchMode());
    }

    private void setSiteListLayout(View view) {
        String[] layouts = ResUtil.getStringArray(R.array.select_site_list_layout);
        int index = (DisplaySettings.getSiteListLayout() + 1) % layouts.length;
        mBinding.siteListLayoutText.setText(layouts[index]);
        DisplaySettings.putSiteListLayout(index);
    }

    private void setResultLayout(View view) {
        String[] layouts = ResUtil.getStringArray(R.array.select_result_layout);
        int index = (DisplaySettings.getSearchView() + 1) % layouts.length;
        mBinding.resultLayoutText.setText(layouts[index]);
        DisplaySettings.putSearchView(index);
    }

    private void setTheme(View view) {
        String[] themes = ResUtil.getStringArray(R.array.select_theme);
        int index = (DisplaySettings.getTheme() + 1) % themes.length;
        mBinding.themeText.setText(themes[index]);
        getIntent().putExtra(THEME_FOCUS_EXTRA, view.getId());
        UiTheme.setAppearance(index);
    }

    private void setColorTheme(View view) {
        String[] themes = ResUtil.getStringArray(R.array.select_color_theme);
        int index = (DisplaySettings.getColorTheme() + 1) % themes.length;
        DisplaySettings.putColorTheme(index);
        getIntent().putExtra(THEME_FOCUS_EXTRA, view.getId());
        recreate();
    }

    private void setLiveScale(View view) {
        String[] scales = ResUtil.getStringArray(R.array.select_scale);
        int index = (DisplaySettings.getLiveScale() + 1) % scales.length;
        mBinding.liveScaleText.setText(scales[index]);
        DisplaySettings.putLiveScale(index);
    }

    private void setUpdate(View view) {
        AppSettings.putUpdate(!AppSettings.getUpdate());
        SettingsOperations.setToggleState(mBinding.updateText, AppSettings.getUpdate());
    }

    private void setRestartMode(View view) {
        int currentMode = AppSettings.getRestartMode();
        int newMode = (currentMode + 1) % 5;
        AppSettings.putRestartMode(newMode);
        mBinding.restartModeText.setText(getRestartModeText());
    }

    private void setLinkRoute(View view) {
        SettingsOperations.toggleLinkRoute(mBinding.linkRouteText);
    }

    private void onReloadQuit(View view) {
        // Reload config and quit the app
        VodConfig.get().init().load(lifecycleCallback(new Callback() {
            @Override
            public void success() {
                // Quit the app after reload
                finishAffinity();
                System.exit(0);
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
                // Still quit even if reload failed
                finishAffinity();
                System.exit(0);
            }
        }));
    }

    private void setSize(View view) {
        int index = (DisplaySettings.getSize() + 1) % size.length;
        mBinding.sizeText.setText(size[index]);
        DisplaySettings.putSize(index);
        RefreshEvent.size();
    }

    private void setDoh(View view) {
        DohDialog.create(this).index(SettingsOperations.getDohIndex()).show();
    }

    private void setGithubProxy(View view) {
        GitHubProxyList.get().init();
        String[] hosts = GitHubProxyList.get().getProxyHosts();
        int next = (GitHubProxyList.get().indexOfHost(AppSettings.getGithubProxyHost()) + 1) % hosts.length;
        SettingsOperations.setGithubProxy(hosts[next], mBinding.githubProxyText);
    }

    private void onGithubProxySpeed(View view) {
        SettingsOperations.testGitHubProxies(this, this, mBinding.githubProxyText, true);
    }

    @Override
    public void setDoh(Doh doh) {
        OkHttp.dns().setDoh(doh);
        AppSettings.putDoh(doh.toString());
        mBinding.dohText.setText(doh.getName());
    }

    private void onCache(View view) {
        WeakReference<SettingActivity> activityRef = new WeakReference<>(this);
        FileUtil.clearCache(lifecycleCallback(new Callback() {
            @Override
            public void success() {
                SettingActivity activity = activityRef.get();
                if (activity != null && !activity.isFinishing() && !activity.isDestroyed() && activity.mBinding != null) {
                    activity.setCacheText();
                }
            }
            @Override
            public void success(String message) {
                success();
                Notify.show(message);
            }
            @Override
            public void error(String message) {
                Notify.show(message);
            }
        }));
    }

    private void onBackup(View view) {
        PermissionUtil.requestFile(this, allGranted -> BackupDialog.create(getActivity()).show(repeatedLifecycleCallback(new Callback() {
            @Override
            public void success() {
                Notify.show(R.string.backup_success);
                setOtherText();
                initConfig();
            }

            @Override
            public void error() {
                Notify.show(R.string.backup_fail);
            }
        })));
    }

    private void onRestore(View view) {
        PermissionUtil.requestFile(this, allGranted -> RestoreDialog.create(getActivity()).show(repeatedLifecycleCallback(new Callback() {
            @Override
            public void success() {
                Notify.show(R.string.restore_success);
                setOtherText();
                initConfig();
            }

            @Override
            public void error() {
                Notify.show(R.string.restore_fail);
            }
        })));
    }

    private void initConfig() {
        VodConfig.get().init().load(getCallback());
        LiveConfig.get().init().load();
        WallConfig.get().init().load();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        if (event.type() != ConfigEvent.Type.COMMON) return;
        mBinding.vodUrl.setText(VodConfig.getDesc());
        mBinding.liveUrl.setText(LiveConfig.getDesc());
        mBinding.wallUrl.setText(WallConfig.getDesc());
    }

    private final ActivityResultLauncher<Intent> launcher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() != RESULT_OK || result.getData() == null || result.getData().getData() == null) return;
        int configType = type;
        FileChooser.importFile(this, result.getData().getData(), path -> {
            String fileUrl = "file:/" + path.replace(Path.rootPath(), "");
            return Config.find(fileUrl, configType);
        }, this::setConfig);
    });

    @Override
    protected void onDestroy() {
        mBinding = null;
        super.onDestroy();
    }
}
