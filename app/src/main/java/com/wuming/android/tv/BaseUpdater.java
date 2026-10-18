package com.wuming.android.tv;
import com.wuming.android.tv.setting.AppSettings;

import android.app.Activity;
import android.view.View;

import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import com.wuming.android.tv.databinding.DialogUpdateBinding;
import com.wuming.android.tv.utils.AppLog;
import com.wuming.android.tv.utils.Download;
import com.wuming.android.tv.utils.DialogUtil;
import com.wuming.android.tv.utils.FileUtil;
import com.wuming.android.tv.utils.Github;
import com.wuming.android.tv.utils.Notify;
import com.wuming.android.tv.utils.ResUtil;
import com.wuming.android.tv.utils.Task;
import com.wuming.android.tv.utils.UpdateClient;
import com.wuming.android.tv.utils.UpdateDownload;
import com.github.catvod.utils.Path;

import com.wuming.tv.core.design.update.UpdateArtifact;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class BaseUpdater implements Download.Callback, DefaultLifecycleObserver {
    private static final String TAG = "BaseUpdater";
    private static final AtomicBoolean AUTO_CHECK_STARTED = new AtomicBoolean(false);

    private DialogUpdateBinding binding;
    private UpdateDownload download;
    private UpdateArtifact artifact;
    private volatile boolean destroyed;
    private AlertDialog dialog;
    private WeakReference<LifecycleOwner> lifecycleOwnerRef;
    private boolean forced;

    private File getFile(UpdateArtifact selected) {
        // REGRESSION WARNING: do not simplify this to a fixed "update.apk" path.
        // Vivo can reuse stale installer metadata for the same FileProvider content URI:
        // the on-device 651 -> 652 update was rejected as "a newer version is installed"
        // despite the downloaded APK having the correct version and SHA-256. A unique
        // URI allowed the 652 -> 653 in-app update with the same displayed versionName.
        // Keep BOTH version and checksum in the filename so different APK bytes never
        // share an installer URI. See docs/android-updates.md for reproduction/results.
        return Path.cache("update-" + selected.getCode() + "-" + selected.getSha256() + ".apk");
    }

    public static BaseUpdater create() {
        throw new UnsupportedOperationException("Must be called from variant subclass");
    }

    public BaseUpdater force() {
        forced = true;
        Notify.show(R.string.update_check);
        AppSettings.putUpdate(true);
        return this;
    }

    private BaseUpdater check() {
        dismiss();
        return this;
    }

    public void start(Activity activity) {
        if (!AppSettings.getUpdate() || BuildConfig.BUILD_TYPE.equals("verification")) return;
        // Home activities may be recreated for configuration changes or task
        // navigation. Automatic update metadata only needs one fetch per app
        // process; explicit checks from Settings always remain available.
        if (!forced && !AUTO_CHECK_STARTED.compareAndSet(false, true)) return;
        observe(activity);
        WeakReference<Activity> activityRef = new WeakReference<>(activity);
        Task.execute(() -> doInBackground(activityRef));
    }

    private void doInBackground(WeakReference<Activity> activityRef) {
        try {
            UpdateArtifact selected = UpdateClient.check(Github.getUpdateFeeds(),
                    BuildConfig.UPDATE_REPOSITORY, BuildConfig.BUILD_TYPE, BuildConfig.FLAVOR_mode,
                    BuildConfig.FLAVOR_abi, BuildConfig.APPLICATION_ID, BuildConfig.VERSION_CODE);
            App.post(() -> {
                Activity activity = activityRef.get();
                if (destroyed || !isActive(activity)) return;
                if (selected == null) {
                    if (forced) Notify.show(R.string.update_latest);
                    removeObserver();
                    return;
                }
                artifact = selected;
                download = new UpdateDownload(selected, getFile(selected));
                show(activity, selected.getName(), selected.getDescription());
            });
        } catch (Exception e) {
            AUTO_CHECK_STARTED.set(false);
            AppLog.e(TAG, e);
            App.post(() -> {
                if (!destroyed && forced) Notify.show(R.string.update_failed);
                removeObserver();
            });
        }
    }

    private void show(Activity activity, String version, String desc) {
        if (!isActive(activity)) return;
        check();
        binding = DialogUpdateBinding.inflate(DialogUtil.inflater(activity));
        createDialog(activity, ResUtil.getString(R.string.update_version, version));
        AlertDialog created = dialog;
        if (created == null) return;
        created.setOnDismissListener(value -> clearDialog(created));
        observe(activity);
        dialog.show();
        onDialogShown(binding, desc);
    }

    protected abstract void createDialog(Activity activity, String title);

    protected abstract void onDialogShown(DialogUpdateBinding binding, String desc);

    protected void setDialog(AlertDialog dialog) {
        this.dialog = dialog;
    }

    protected AlertDialog getDialog() {
        return dialog;
    }

    protected DialogUpdateBinding getBinding() {
        return binding;
    }

    protected void cancel(View view) {
        destroyed = true;
        if (download != null) download.cancel();
        dismiss();
    }

    protected void confirm(View view) {
        view.setEnabled(false);
        if (download != null) download.start(this);
    }

    private void dismiss() {
        AlertDialog current = dialog;
        dialog = null;
        binding = null;
        removeObserver();
        try {
            if (current != null) current.dismiss();
        } catch (Exception ignored) {
        }
    }

    private void clearDialog(AlertDialog dismissed) {
        if (dialog != dismissed) return;
        destroyed = true;
        if (download != null) download.cancel();
        dialog = null;
        binding = null;
        removeObserver();
    }

    private void observe(Activity activity) {
        if (!(activity instanceof LifecycleOwner)) return;
        LifecycleOwner owner = (LifecycleOwner) activity;
        lifecycleOwnerRef = new WeakReference<>(owner);
        owner.getLifecycle().addObserver(this);
    }

    private void removeObserver() {
        LifecycleOwner owner = lifecycleOwnerRef == null ? null : lifecycleOwnerRef.get();
        lifecycleOwnerRef = null;
        if (owner != null) owner.getLifecycle().removeObserver(this);
    }

    @Override
    public void onDestroy(LifecycleOwner owner) {
        LifecycleOwner observed = lifecycleOwnerRef == null ? null : lifecycleOwnerRef.get();
        if (observed != owner) return;
        destroyed = true;
        if (artifact == null) AUTO_CHECK_STARTED.set(false);
        if (download != null) download.cancel();
        dismiss();
    }

    private static boolean isActive(Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    @Override
    public void error(String msg) {
        Notify.show(msg);
        dismiss();
    }

    @Override
    public void success(File file) {
        if (destroyed) return;
        try { FileUtil.openFile(file); }
        catch (Exception e) { AppLog.e(TAG, e); Notify.show(R.string.update_failed); }
        dismiss();
    }
}
