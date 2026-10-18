package com.wuming.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.wuming.android.tv.R;
import com.wuming.android.tv.cloud.CloudStorageManager;
import com.wuming.android.tv.cloud.CloudStorageProvider;
import com.wuming.android.tv.cloud.CloudStorageRepository;
import com.wuming.android.tv.databinding.ActivityCloudStorageCacheBinding;
import com.wuming.android.tv.ui.adapter.CloudStorageAdapter;
import com.wuming.android.tv.ui.base.BaseActivity;
import com.wuming.android.tv.utils.FileUtil;
import com.wuming.android.tv.utils.Notify;
import com.wuming.android.tv.utils.Task;
import com.wuming.android.tv.utils.TvAlertDialogBuilder;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/** Secondary screen for app-owned remote cache inspection and cleanup. */
public class CloudStorageCacheActivity extends BaseActivity implements CloudStorageAdapter.Listener {

    private ActivityCloudStorageCacheBinding binding;
    private CloudStorageAdapter adapter;
    private final Set<Future<?>> pendingTasks = ConcurrentHashMap.newKeySet();

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, CloudStorageCacheActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = ActivityCloudStorageCacheBinding.inflate(getLayoutInflater());
    }

    @Override
    protected boolean customWall() {
        return false;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        binding.back.setOnClickListener(view -> finish());
        binding.list.setLayoutManager(new LinearLayoutManager(this));
        binding.list.setAdapter(adapter = new CloudStorageAdapter(
                Arrays.asList(CloudStorageProvider.values()), this));
        loadStats();
    }

    @Override
    public void onManage(CloudStorageProvider provider) {
        CloudStorageWebActivity.startManagement(this, provider);
    }

    @Override
    public void onClear(CloudStorageProvider provider) {
        new TvAlertDialogBuilder(this)
                .setTitle(R.string.cloud_storage_clear_title)
                .setMessage(getString(R.string.cloud_storage_clear_confirm,
                        provider.getDisplayName(), CloudStorageManager.CACHE_FOLDER))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.cloud_storage_clear,
                        (dialog, which) -> clear(provider))
                .show();
    }

    private void clear(CloudStorageProvider provider) {
        adapter.setBusy(provider, true);
        track(CloudStorageManager.clearAppCache(provider, new ClearCallback(this, provider)));
    }

    private static final class ClearCallback implements CloudStorageManager.Callback {
        private final WeakReference<CloudStorageCacheActivity> activityRef;
        private final CloudStorageProvider provider;

        ClearCallback(CloudStorageCacheActivity activity, CloudStorageProvider provider) {
            activityRef = new WeakReference<>(activity);
            this.provider = provider;
        }

        @Override
        public void onComplete(CloudStorageManager.Result result) {
            CloudStorageCacheActivity activity = activityRef.get();
            if (!alive(activity)) return;
            activity.adapter.setBusy(provider, false);
            if (!result.supported) {
                new TvAlertDialogBuilder(activity)
                        .setTitle(R.string.cloud_storage_provider_unsupported_title)
                        .setMessage(R.string.cloud_storage_provider_unsupported)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.cloud_storage_manage,
                                (dialog, which) -> activity.onManage(provider))
                        .show();
                return;
            }
            activity.loadStats();
            String freed = FileUtil.byteCountToDisplaySize(result.freedBytes);
            String text = activity.getString(
                    R.string.cloud_storage_clear_result, result.deleted, freed);
            if (!result.failedIds.isEmpty() || !result.skippedIds.isEmpty())
                text += "\n" + activity.getString(R.string.cloud_storage_clear_incomplete,
                        result.failedIds.size(), result.skippedIds.size());
            if (!result.message.isEmpty()) text += "\n" + result.message;
            Notify.show(text);
        }
    }

    private void loadStats() {
        submit(new StatsTask(this));
    }

    private static final class StatsTask implements Runnable {
        private final WeakReference<CloudStorageCacheActivity> activityRef;

        StatsTask(CloudStorageCacheActivity activity) {
            activityRef = new WeakReference<>(activity);
        }

        @Override
        public void run() {
            CloudStorageRepository.Stats[] values =
                    new CloudStorageRepository.Stats[CloudStorageProvider.values().length];
            for (CloudStorageProvider provider : CloudStorageProvider.values()) {
                if (Thread.currentThread().isInterrupted()) return;
                values[provider.ordinal()] = CloudStorageRepository.stats(provider);
            }
            CloudStorageCacheActivity activity = activityRef.get();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                CloudStorageCacheActivity current = activityRef.get();
                if (!alive(current)) return;
                for (CloudStorageProvider provider : CloudStorageProvider.values()) {
                    current.adapter.setStats(provider, values[provider.ordinal()]);
                }
            });
        }
    }

    private void submit(Runnable runnable) {
        track(Task.submitLarge(runnable));
    }

    private void track(Future<?> future) {
        if (future == null) return;
        pendingTasks.removeIf(Future::isDone);
        pendingTasks.add(future);
        if (isDestroyed()) future.cancel(true);
    }

    private static boolean alive(CloudStorageCacheActivity activity) {
        return activity != null && activity.binding != null
                && !activity.isFinishing() && !activity.isDestroyed();
    }

    @Override
    protected void onDestroy() {
        for (Future<?> task : pendingTasks) task.cancel(true);
        pendingTasks.clear();
        if (binding != null) {
            binding.back.setOnClickListener(null);
            binding.list.setAdapter(null);
        }
        adapter = null;
        binding = null;
        super.onDestroy();
    }
}
