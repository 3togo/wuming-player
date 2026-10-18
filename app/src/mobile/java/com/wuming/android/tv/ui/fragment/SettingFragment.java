package com.wuming.android.tv.ui.fragment;
import com.wuming.android.tv.setting.DisplaySettings;
import com.wuming.android.tv.setting.AppSettings;
import com.wuming.android.tv.setting.UiTheme;

import android.app.Activity;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
import com.wuming.android.tv.databinding.FragmentSettingBinding;
import com.wuming.android.tv.event.ConfigEvent;
import com.wuming.android.tv.event.RefreshEvent;
import com.wuming.android.tv.impl.Callback;
import com.wuming.android.tv.impl.ConfigCallback;
import com.wuming.android.tv.impl.LiveCallback;
import com.wuming.android.tv.impl.SiteCallback;
import com.wuming.android.tv.ui.SettingsOperations;
import com.wuming.android.tv.ui.activity.CloudStorageActivity;
import com.wuming.android.tv.ui.activity.HomeActivity;
import com.wuming.android.tv.ui.base.BaseFragment;
import com.wuming.android.tv.ui.dialog.MobileConfigDialog;
import com.wuming.android.tv.ui.dialog.HistoryDialog;
import com.wuming.android.tv.ui.dialog.LiveDialog;
import com.wuming.android.tv.ui.dialog.BackupDialog;
import com.wuming.android.tv.ui.dialog.RestoreDialog;
import com.wuming.android.tv.ui.dialog.MobileSiteDialog;
import com.wuming.android.tv.ui.dialog.DepotDialog;
import com.wuming.android.tv.utils.FileChooser;
import com.wuming.android.tv.utils.FileUtil;
import com.wuming.android.tv.utils.Notify;
import com.wuming.android.tv.utils.PermissionUtil;
import com.wuming.android.tv.utils.ResUtil;
import com.wuming.android.tv.utils.TextScale;
import com.wuming.android.tv.utils.DepotUtil;
import com.wuming.android.tv.utils.GitHubProxyList;
import com.github.catvod.bean.Doh;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;
import androidx.appcompat.app.AlertDialog;
import com.wuming.android.tv.utils.TvAlertDialogBuilder;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.lang.ref.WeakReference;

public class SettingFragment extends BaseFragment implements ConfigCallback, SiteCallback, LiveCallback {

    private FragmentSettingBinding mBinding;
    private AlertDialog mChoiceDialog;
    private String[] size;
    private String[] uiScaleCap;
    private String[] uiScaleBaseline;
    private int type;

    public static SettingFragment newInstance() {
        return new SettingFragment();
    }

    private HomeActivity getRoot() {
        return (HomeActivity) requireActivity();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentSettingBinding.inflate(inflater, container, false);
    }

    @Override
    protected void releaseBinding() {
        mBinding = null;
    }

    @Override
    protected void initView() {
        EventBus.getDefault().register(this);
        mBinding.vodUrl.setText(VodConfig.getDesc());
        mBinding.liveUrl.setText(LiveConfig.getDesc());
        mBinding.wallUrl.setText(WallConfig.getDesc());
        mBinding.versionText.setText(BuildConfig.VERSION_NAME);
        SettingsOperations.setLinkRouteText(mBinding.linkRouteText);

        setDepotText();
        setOtherText();
        setCacheText();
    }

    private void setDepotText() {
        mBinding.depotText.setText(DepotUtil.getDepotText(requireContext()));
    }

    private void setOtherText() {
        mBinding.dohText.setText(SettingsOperations.getDohNames()[SettingsOperations.getDohIndex()]);
        SettingsOperations.setToggleState(mBinding.incognitoText, AppSettings.isIncognito());
        mBinding.sizeText.setText((size = ResUtil.getStringArray(R.array.select_size))[DisplaySettings.getSize()]);
        mBinding.themeText.setText(ResUtil.getStringArray(R.array.select_theme)[DisplaySettings.getTheme()]);
        mBinding.colorThemeText.setText(ResUtil.getStringArray(R.array.select_color_theme)[DisplaySettings.getColorTheme()]);
        mBinding.githubProxyText.setText(SettingsOperations.getGithubProxyName());
        uiScaleCap = ResUtil.getStringArray(R.array.select_ui_scale_cap);
        uiScaleBaseline = ResUtil.getStringArray(R.array.select_ui_scale_baseline);
        mBinding.uiScaleCapText.setText(uiScaleCap[uiScaleCapIndex()]);
        mBinding.uiScaleBaselineText.setText(uiScaleBaseline[uiScaleBaselineIndex()]);
    }

    private void setCacheText() {
        SettingsOperations.loadCacheSize(getViewLifecycleOwner(), mBinding.cacheText);
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
        mBinding.theme.setOnClickListener(this::setTheme);
        mBinding.colorTheme.setOnClickListener(this::setColorTheme);
        mBinding.uiScaleCap.setOnClickListener(this::setUiScaleCap);
        mBinding.uiScaleBaseline.setOnClickListener(this::setUiScaleBaseline);
        mBinding.cache.setOnClickListener(this::onCache);
        com.wuming.android.tv.ui.HardwareCapabilitySettings.bind(mBinding.videoCapabilities);
        mBinding.cloudStorage.setOnClickListener(view ->
                CloudStorageActivity.start(requireActivity()));
        mBinding.linkRoute.setOnClickListener(this::setLinkRoute);
        mBinding.backup.setOnClickListener(this::onBackup);
        mBinding.player.setOnClickListener(this::onPlayer);
        mBinding.desktopRelay.setOnClickListener(view -> com.wuming.android.tv.player.relay.DesktopRelayDialog.show(requireActivity()));
        mBinding.restore.setOnClickListener(this::onRestore);
        mBinding.version.setOnClickListener(this::onVersion);
        mBinding.vod.setOnLongClickListener(this::onVodEdit);
        mBinding.vodHome.setOnClickListener(this::onVodHome);
        mBinding.live.setOnLongClickListener(this::onLiveEdit);
        mBinding.liveHome.setOnClickListener(this::onLiveHome);
        mBinding.wall.setOnLongClickListener(this::onWallEdit);
        mBinding.incognito.setOnClickListener(this::setIncognito);
        mBinding.vodHistory.setOnClickListener(this::onVodHistory);
        mBinding.vodDefault.setOnClickListener(this::onVodDefault);
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
        DepotDialog.create(requireContext(), this::addDepotConfig).show();
    }

    private void addDepotConfig(android.content.Context context, String url, int type, String name, boolean editUrl) {
        if (editUrl) MobileConfigDialog.create(this).launcher(launcher).type(type).url(url).unlockedUrl().show();
        else setConfig(Config.find(url, name, type));
    }

    private void onDepotUpdate(View view) {
        SettingsOperations.updateDepot(requireActivity(), getViewLifecycleOwner(), mBinding.depotText);
    }

    private void onDepotValidate(View view) {
        SettingsOperations.validateDepot(requireActivity(), getViewLifecycleOwner(), mBinding.depotText);
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
                Notify.progress(requireActivity());
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
        MobileConfigDialog.create(this).launcher(launcher).type(type = 0).show();
    }

    private void onLive(View view) {
        MobileConfigDialog.create(this).launcher(launcher).type(type = 1).show();
    }

    private void onWall(View view) {
        MobileConfigDialog.create(this).launcher(launcher).type(type = 2).show();
    }

    private boolean onVodEdit(View view) {
        MobileConfigDialog.create(this).launcher(launcher).type(type = 0).edit().show();
        return true;
    }

    private boolean onLiveEdit(View view) {
        MobileConfigDialog.create(this).launcher(launcher).type(type = 1).edit().show();
        return true;
    }

    private boolean onWallEdit(View view) {
        MobileConfigDialog.create(this).launcher(launcher).type(type = 2).edit().show();
        return true;
    }

    private void onVodHome(View view) {
        MobileSiteDialog.create(this).all().show();
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

    private void onLiveHistory(View view) {
        HistoryDialog.create(this).type(type = 1).show();
    }

    private void onLiveDefault(View view) {
        Config.defaultsForce();
        Notify.show(R.string.setting_default);
    }

    private void onPlayer(View view) {
        getRoot().change(2);
    }

    private void onVersion(View view) {
        Updater.create().force().start(requireActivity());
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

    private void setLinkRoute(View view) {
        SettingsOperations.toggleLinkRoute(mBinding.linkRouteText);
    }

    private void setSize(View view) {
        mChoiceDialog = new TvAlertDialogBuilder(requireActivity()).setTitle(R.string.setting_size).setNegativeButton(R.string.dialog_negative, null).setSingleChoiceItems(size, DisplaySettings.getSize(), (dialog, which) -> {
            mBinding.sizeText.setText(size[which]);
            DisplaySettings.putSize(which);
            RefreshEvent.size();
            dialog.dismiss();
        }).show();
    }

    private void setTheme(View view) {
        String[] themes = ResUtil.getStringArray(R.array.select_theme);
        new TvAlertDialogBuilder(requireActivity()).setTitle(R.string.setting_theme).setNegativeButton(R.string.dialog_negative, null).setSingleChoiceItems(themes, DisplaySettings.getTheme(), (dialog, which) -> {
            mBinding.themeText.setText(themes[which]);
            dialog.dismiss();
            UiTheme.setAppearance(which);
        }).show();
    }

    private void setColorTheme(View view) {
        String[] themes = ResUtil.getStringArray(R.array.select_color_theme);
        new TvAlertDialogBuilder(requireActivity()).setTitle(R.string.setting_color_theme).setNegativeButton(R.string.dialog_negative, null).setSingleChoiceItems(themes, DisplaySettings.getColorTheme(), (dialog, which) -> {
            if (which != DisplaySettings.getColorTheme()) {
                DisplaySettings.putColorTheme(which);
                dialog.dismiss();
                requireActivity().recreate();
                return;
            }
            dialog.dismiss();
        }).show();
    }

    private static final float[] UI_SCALE_CAP_VALUES = {1.0f, 1.1f, 1.25f, 1.4f};
    private static final float[] UI_SCALE_BASELINE_VALUES = {320f, 360f, 400f};

    private int uiScaleCapIndex() {
        float cap = DisplaySettings.getUiTextScaleCap();
        for (int i = 0; i < UI_SCALE_CAP_VALUES.length; i++) {
            if (Math.abs(UI_SCALE_CAP_VALUES[i] - cap) < 0.001f) return i;
        }
        return 2; // default 1.25x
    }

    private int uiScaleBaselineIndex() {
        float baseline = DisplaySettings.getUiTextScaleBaseline();
        for (int i = 0; i < UI_SCALE_BASELINE_VALUES.length; i++) {
            if (Math.abs(UI_SCALE_BASELINE_VALUES[i] - baseline) < 0.001f) return i;
        }
        return 1; // default 360dp
    }

    private void setUiScaleCap(View view) {
        int index = uiScaleCapIndex();
        mChoiceDialog = new TvAlertDialogBuilder(requireActivity()).setTitle(R.string.setting_ui_scale_cap).setNegativeButton(R.string.dialog_negative, null).setSingleChoiceItems(uiScaleCap, index, (dialog, which) -> {
            DisplaySettings.putUiTextScaleCap(UI_SCALE_CAP_VALUES[which]);
            mBinding.uiScaleCapText.setText(uiScaleCap[which]);
            applyUiScaleChange();
            dialog.dismiss();
        }).show();
    }

    private void setUiScaleBaseline(View view) {
        int index = uiScaleBaselineIndex();
        mChoiceDialog = new TvAlertDialogBuilder(requireActivity()).setTitle(R.string.setting_ui_scale_baseline).setNegativeButton(R.string.dialog_negative, null).setSingleChoiceItems(uiScaleBaseline, index, (dialog, which) -> {
            DisplaySettings.putUiTextScaleBaseline(UI_SCALE_BASELINE_VALUES[which]);
            mBinding.uiScaleBaselineText.setText(uiScaleBaseline[which]);
            applyUiScaleChange();
            dialog.dismiss();
        }).show();
    }

    /** Re-apply scaling to the current screen at once and warn about other screens. */
    private void applyUiScaleChange() {
        TextScale.resetAndApply(mBinding.getRoot());
        RefreshEvent.size();
        Toast.makeText(requireActivity(), R.string.setting_ui_scale_applied, Toast.LENGTH_SHORT).show();
    }

    private void setDoh(View view) {
        mChoiceDialog = new TvAlertDialogBuilder(requireActivity()).setTitle(R.string.setting_doh).setNegativeButton(R.string.dialog_negative, null).setSingleChoiceItems(SettingsOperations.getDohNames(), SettingsOperations.getDohIndex(), (dialog, which) -> {
            setDoh(VodConfig.get().getDoh().get(which));
            dialog.dismiss();
        }).show();
    }

    private void setDoh(Doh doh) {
        OkHttp.dns().setDoh(doh);
        AppSettings.putDoh(doh.toString());
        mBinding.dohText.setText(doh.getName());
    }

    private void setGithubProxy(View view) {
        GitHubProxyList.get().init();
        String[] names = GitHubProxyList.get().getProxyNames();
        String[] hosts = GitHubProxyList.get().getProxyHosts();
        int checked = GitHubProxyList.get().indexOfHost(AppSettings.getGithubProxyHost());
        mChoiceDialog = new TvAlertDialogBuilder(requireActivity()).setTitle(R.string.setting_github_proxy).setNegativeButton(R.string.dialog_negative, null).setSingleChoiceItems(names, checked, (dialog, which) -> {
            SettingsOperations.setGithubProxy(hosts[which], mBinding.githubProxyText);
            dialog.dismiss();
        }).show();
    }

    private void onGithubProxySpeed(View view) {
        SettingsOperations.testGitHubProxies(requireActivity(), getViewLifecycleOwner(), mBinding.githubProxyText, false);
    }

    private void onCache(View view) {
        WeakReference<SettingFragment> fragmentRef = new WeakReference<>(this);
        FileUtil.clearCache(lifecycleCallback(new Callback() {
            @Override
            public void success() {
                SettingFragment fragment = fragmentRef.get();
                if (fragment != null && fragment.isAdded() && fragment.mBinding != null) {
                    fragment.setCacheText();
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
        PermissionUtil.requestFile(this, allGranted -> BackupDialog.create().show(requireActivity(), repeatedLifecycleCallback(new Callback() {
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
        PermissionUtil.requestFile(this, allGranted -> RestoreDialog.create().show(requireActivity(), repeatedLifecycleCallback(new Callback() {
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

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mChoiceDialog != null && mChoiceDialog.isShowing()) mChoiceDialog.dismiss();
        mChoiceDialog = null;
        EventBus.getDefault().unregister(this);
    }

    private final ActivityResultLauncher<Intent> launcher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) return;
        if (getView() == null) return;
        int configType = type;
        FileChooser.importFile(getViewLifecycleOwner(), result.getData().getData(), path -> {
            String fileUrl = "file:/" + path.replace(Path.rootPath(), "");
            return Config.find(fileUrl, configType);
        }, this::setConfig);
    });
}
