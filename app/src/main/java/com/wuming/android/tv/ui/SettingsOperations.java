package com.wuming.android.tv.ui;
import com.wuming.android.tv.setting.AppSettings;

import android.app.Activity;
import android.view.View;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import com.wuming.android.tv.App;
import com.wuming.android.tv.R;
import com.wuming.android.tv.api.config.VodConfig;
import com.wuming.android.tv.bean.GitHubProxy;
import com.wuming.android.tv.impl.Callback;
import com.wuming.android.tv.utils.DepotManager;
import com.wuming.android.tv.utils.FileUtil;
import com.wuming.android.tv.utils.GitHubProxyList;
import com.wuming.android.tv.utils.GitHubProxySpeedTest;
import com.wuming.android.tv.utils.NetworkChecker;
import com.wuming.android.tv.utils.Notify;
import com.wuming.android.tv.utils.TvAlertDialogBuilder;
import com.github.catvod.bean.Doh;
import com.wuming.android.tv.utils.ResUtil;
import com.wuming.tv.core.source.GitHubMirror;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/** Shared asynchronous workflows used by both Mobile and Leanback settings. */
public final class SettingsOperations {

    private SettingsOperations() {
    }

    public static void refreshVideoCapabilities(View trigger) {
        trigger.setEnabled(false);
        Notify.show(R.string.video_capabilities_checking);
        WeakReference<View> target = new WeakReference<>(trigger);
        com.wuming.android.tv.utils.Task.submit(() -> {
            int count;
            try {
                count = com.wuming.android.tv.player.exo.VideoCapabilityCache.get().refresh();
            } catch (Exception error) {
                com.wuming.android.tv.utils.AppLog.w("VideoCapabilities", "Refresh failed", error);
                count = -1;
            }
            int result = count;
            App.post(() -> {
                View view = target.get();
                if (view == null) return;
                view.setEnabled(true);
                HardwareCapabilitySettings.render(view);
                if (!view.isAttachedToWindow()) return;
                Notify.show(result < 0 ? view.getContext().getString(R.string.video_capabilities_failed)
                        : view.getContext().getString(R.string.video_capabilities_updated, result));
            });
        });
    }

    public static int getDohIndex() {
        return Math.max(0, VodConfig.get().getDoh().indexOf(Doh.objectFrom(AppSettings.getDoh())));
    }

    public static String[] getDohNames() {
        List<String> names = new ArrayList<>();
        for (Doh item : VodConfig.get().getDoh()) names.add(item.getName());
        return names.toArray(new String[0]);
    }

    public static void setToggleState(TextView target, boolean enabled) {
        target.setText(enabled ? R.string.setting_on : R.string.setting_off);
        target.setTextColor(ContextCompat.getColor(target.getContext(),
                enabled ? R.color.setting_toggle_on : R.color.setting_toggle_off));
    }

    public static void setLinkRouteText(TextView target) {
        target.setText(AppSettings.getLinkRoute() == AppSettings.LINK_ROUTE_BUILTIN
                ? R.string.link_route_builtin : R.string.link_route_direct);
    }

    public static void toggleLinkRoute(TextView target) {
        int route = AppSettings.getLinkRoute() == AppSettings.LINK_ROUTE_BUILTIN
                ? AppSettings.LINK_ROUTE_DIRECT : AppSettings.LINK_ROUTE_BUILTIN;
        AppSettings.putLinkRoute(route);
        setLinkRouteText(target);
    }

    /**
     * Binds the common video-data preferences without imposing a Mobile or Leanback widget.
     * Each variant remains responsible for layout, focus navigation, and visual feedback.
     */
    public static void bindVideoNetworkSettings(
            View mobileTrigger, TextView mobileValue,
            View roamingTrigger, TextView roamingValue) {
        Runnable render = () -> {
            setToggleState(mobileValue, AppSettings.isMobileVideoAllowed());
            setToggleState(roamingValue, AppSettings.isRoamingVideoAllowed());
        };
        mobileTrigger.setOnClickListener(view -> {
            AppSettings.putMobileVideoAllowed(!AppSettings.isMobileVideoAllowed());
            render.run();
        });
        roamingTrigger.setOnClickListener(view -> {
            AppSettings.putRoamingVideoAllowed(!AppSettings.isRoamingVideoAllowed());
            render.run();
        });
        render.run();
    }

    public static void bindLegacyTlsSetting(Activity activity, View trigger, TextView value) {
        setToggleState(value, AppSettings.isLegacyTlsEnabled());
        trigger.setOnClickListener(view -> {
            if (AppSettings.isLegacyTlsEnabled()) {
                AppSettings.putLegacyTlsEnabled(false);
                setToggleState(value, false);
                return;
            }
            new TvAlertDialogBuilder(activity)
                    .setTitle(R.string.setting_legacy_tls)
                    .setMessage(R.string.setting_legacy_tls_warning)
                    .setNegativeButton(R.string.dialog_negative, null)
                    .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                        AppSettings.putLegacyTlsEnabled(true);
                        setToggleState(value, true);
                    })
                    .show();
        });
    }

    public static void loadCacheSize(LifecycleOwner owner, TextView target) {
        WeakReference<LifecycleOwner> ownerRef = new WeakReference<>(owner);
        WeakReference<TextView> targetRef = new WeakReference<>(target);
        FileUtil.getCacheSize(new Callback() {
            @Override
            public void success(String result) {
                LifecycleOwner currentOwner = ownerRef.get();
                TextView currentTarget = targetRef.get();
                if (isAlive(currentOwner) && currentTarget != null) currentTarget.setText(result);
            }
            @Override
            public void error(String message) {
                LifecycleOwner currentOwner = ownerRef.get();
                TextView currentTarget = targetRef.get();
                if (isAlive(currentOwner) && currentTarget != null) currentTarget.setText(message);
            }
        });
    }

    public static void updateDepot(Activity activity, LifecycleOwner owner, TextView target) {
        runDepotOperation(activity, owner, target, false);
    }

    public static void validateDepot(Activity activity, LifecycleOwner owner, TextView target) {
        runDepotOperation(activity, owner, target, true);
    }

    private static void runDepotOperation(Activity activity, LifecycleOwner owner, TextView target, boolean validate) {
        Notify.show(validate ? R.string.setting_depot_validate : R.string.setting_depot_update);
        WeakReference<Activity> activityRef = new WeakReference<>(activity);
        WeakReference<LifecycleOwner> ownerRef = new WeakReference<>(owner);
        WeakReference<TextView> targetRef = new WeakReference<>(target);
        DepotManager.Callback callback = new DepotManager.Callback() {
            @Override
            public void onResult(String message) {
                post(() -> {
                    Notify.show(message);
                    setText(com.wuming.android.tv.utils.DepotUtil.getDepotText(activity));
                });
            }

            @Override
            public void onProgress(int done, int total) {
                // Text only: a toast per source would fire ~100 notifications.
                post(() -> setText(activity.getString(R.string.setting_depot_progress, done, total)));
            }

            private void post(Runnable task) {
                Activity currentActivity = activityRef.get();
                LifecycleOwner currentOwner = ownerRef.get();
                if (!isAlive(currentActivity, currentOwner)) return;
                App.post(() -> {
                    Activity aliveActivity = activityRef.get();
                    LifecycleOwner aliveOwner = ownerRef.get();
                    if (!isAlive(aliveActivity, aliveOwner)) return;
                    task.run();
                });
            }

            private void setText(String text) {
                TextView currentTarget = targetRef.get();
                if (currentTarget != null) currentTarget.setText(text);
            }
        };
        if (validate) DepotManager.validateSources(activity, callback);
        else DepotManager.updateFromGitHub(activity, callback);
    }

    /** The label of the mirror this device uses, in the user's language when it is one we ship. */
    public static String getGithubProxyName() {
        String host = AppSettings.getGithubProxyHost();
        String[] names = ResUtil.getStringArray(R.array.github_proxy_name);
        int builtIn = GitHubMirror.hostIndexOf(host);
        if (builtIn >= 0 && builtIn < names.length) return names[builtIn];
        String external = GitHubProxyList.get().nameOf(host);
        return external != null ? external : host;
    }

    public static void setGithubProxy(String host, TextView target) {
        AppSettings.putGithubProxyHost(host);
        target.setText(getGithubProxyName());
    }

    public static void testGitHubProxies(Activity activity, LifecycleOwner owner, TextView target, boolean usePlatformDialog) {
        GitHubProxyList.get().init();
        List<GitHubProxy> proxies = GitHubProxyList.get().getProxies();
        String testUrl = GitHubProxyList.get().getTestUrl();
        Notify.progress(activity);

        WeakReference<Activity> activityRef = new WeakReference<>(activity);
        WeakReference<LifecycleOwner> ownerRef = new WeakReference<>(owner);
        WeakReference<TextView> targetRef = new WeakReference<>(target);
        GitHubProxySpeedTest.runTestParallel(proxies, testUrl, (results, bestIndex) -> {
            Activity currentActivity = activityRef.get();
            LifecycleOwner currentOwner = ownerRef.get();
            TextView currentTarget = targetRef.get();
            if (!isAlive(currentActivity, currentOwner) || currentTarget == null) return;
            Notify.dismiss();

            String[] names = GitHubProxyList.get().getProxyNames();
            if (bestIndex >= 0) {
                setGithubProxy(GitHubProxyList.get().getProxyHosts()[bestIndex], currentTarget);
            }

            StringBuilder message = new StringBuilder();
            for (GitHubProxySpeedTest.Result result : results) {
                message.append(result.getDisplayText()).append('\n');
            }
            if (bestIndex >= 0) {
                message.append('\n').append(currentActivity.getString(
                        R.string.setting_github_proxy_fastest,
                        names[bestIndex],
                        results.get(bestIndex).responseTime));
            }
            String resultMessage = message.toString().trim();
            if (usePlatformDialog) {
                android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(currentActivity)
                        .setTitle(R.string.setting_github_proxy_speed)
                        .setMessage(resultMessage)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                com.wuming.android.tv.utils.TextScale.applyDialog(dialog.getWindow().getDecorView());
            } else {
                new TvAlertDialogBuilder(currentActivity)
                        .setTitle(R.string.setting_github_proxy_speed)
                        .setMessage(resultMessage)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
        });
    }

    public static void testIPv6(Activity activity, LifecycleOwner owner, View trigger, TextView target) {
        trigger.setEnabled(false);
        target.setText(R.string.ipv6_test_checking);

        WeakReference<Activity> activityRef = new WeakReference<>(activity);
        WeakReference<LifecycleOwner> ownerRef = new WeakReference<>(owner);
        WeakReference<View> triggerRef = new WeakReference<>(trigger);
        WeakReference<TextView> targetRef = new WeakReference<>(target);
        NetworkChecker.testIPv6Connectivity(result -> App.post(() -> {
            Activity currentActivity = activityRef.get();
            LifecycleOwner currentOwner = ownerRef.get();
            View currentTrigger = triggerRef.get();
            TextView currentTarget = targetRef.get();
            if (!isAlive(currentActivity, currentOwner) || currentTrigger == null || currentTarget == null) return;

            currentTrigger.setEnabled(true);
            currentTarget.setText(result.available() ? R.string.ipv6_test_available : R.string.ipv6_test_unavailable);
            int title = result.available() ? R.string.ipv6_test_available_title : R.string.ipv6_test_unavailable_title;
            String message;
            if (result.available()) {
                message = currentActivity.getString(R.string.ipv6_test_available_message, result.address());
            } else if (result.hasAddress()) {
                message = currentActivity.getString(R.string.ipv6_test_no_route_message, result.address());
            } else {
                message = currentActivity.getString(R.string.ipv6_test_no_address_message);
            }
            new TvAlertDialogBuilder(currentActivity)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(R.string.dialog_positive, null)
                    .show();
        }));
    }

    private static boolean isAlive(LifecycleOwner owner) {
        return owner != null && owner.getLifecycle().getCurrentState() != Lifecycle.State.DESTROYED;
    }

    private static boolean isAlive(Activity activity, LifecycleOwner owner) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed() && isAlive(owner);
    }
}
