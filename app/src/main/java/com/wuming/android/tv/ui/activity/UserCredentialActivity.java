package com.wuming.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.wuming.android.tv.R;
import com.wuming.android.tv.cloud.opendrive.UserCredentialStore;
import com.wuming.android.tv.databinding.ActivityUserCredentialBinding;
import com.wuming.android.tv.databinding.DialogUserCredentialBinding;
import com.wuming.android.tv.ui.adapter.UserCredentialAdapter;
import com.wuming.android.tv.ui.base.BaseActivity;
import com.wuming.android.tv.utils.Notify;
import com.wuming.android.tv.utils.Task;
import com.wuming.android.tv.utils.TvAlertDialogBuilder;
import com.wuming.tv.core.cloud.OpenDriveUserCredential;
import com.wuming.tv.core.cloud.UserCredentialMethod;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/** Manager for non-netdisk service logins (danmaku / VIP parse / live OAuth). */
public final class UserCredentialActivity extends BaseActivity
        implements UserCredentialAdapter.Listener {

    private ActivityUserCredentialBinding binding;
    private UserCredentialAdapter adapter;
    private final Set<Future<?>> pendingTasks = ConcurrentHashMap.newKeySet();
    private androidx.appcompat.app.AlertDialog activeDialog;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, UserCredentialActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = ActivityUserCredentialBinding.inflate(getLayoutInflater());
    }

    @Override
    protected boolean customWall() {
        return false;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        binding.back.setOnClickListener(view -> finish());
        binding.add.setOnClickListener(view -> showEditor(null));
        binding.list.setLayoutManager(new LinearLayoutManager(this));
        binding.list.setAdapter(adapter = new UserCredentialAdapter(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    @Override
    public void onEdit(OpenDriveUserCredential credential) {
        showEditor(credential);
    }

    @Override
    public void onDelete(OpenDriveUserCredential credential) {
        new TvAlertDialogBuilder(this)
                .setTitle(R.string.credential_delete_title)
                .setMessage(getString(R.string.credential_delete_message, credential.getDisplayName()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.credential_delete,
                        (dialog, which) -> submit(new DeleteTask(this, credential.getServiceId())))
                .show();
    }

    private void reload() {
        submit(new ReloadTask(this));
    }

    private void showEditor(OpenDriveUserCredential credential) {
        if (activeDialog != null) activeDialog.dismiss();
        DialogUserCredentialBinding form = DialogUserCredentialBinding.inflate(
                LayoutInflater.from(this));
        form.secret.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        int methodIndex = 0;
        if (credential != null) {
            form.name.setText(credential.getDisplayName());
            form.serviceId.setText(credential.getServiceId());
            form.serviceId.setEnabled(false);
            form.url.setText(credential.getBaseUrl());
            methodIndex = methodToIndex(credential.getMethod());
            form.secret.setHint(R.string.credential_password_unchanged);
        }
        form.method.setSelection(methodIndex);
        androidx.appcompat.app.AlertDialog dialog = new TvAlertDialogBuilder(this)
                .setTitle(credential == null ? R.string.credential_add : R.string.credential_edit)
                .setView(form.getRoot())
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.setOnShowListener(ignored -> {
            com.wuming.android.tv.utils.TvDialogFocus.form(dialog,
                    form.name, form.serviceId, form.url, form.secret);
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(view -> save(dialog, form, credential));
        });
        dialog.setOnDismissListener(ignored -> {
            if (activeDialog == dialog) activeDialog = null;
        });
        activeDialog = dialog;
        dialog.show();
    }

    private void save(androidx.appcompat.app.AlertDialog dialog,
                      DialogUserCredentialBinding form,
                      OpenDriveUserCredential credential) {
        String displayName = form.name.getText().toString();
        String serviceId = form.serviceId.getText().toString().trim();
        String url = form.url.getText().toString();
        UserCredentialMethod method = indexToMethod(form.method.getSelectedItemPosition());
        String secret = form.secret.getText().toString();
        boolean editing = credential != null;
        if (editing && secret.isEmpty()) {
            String old = UserCredentialStore.getSecret(credential.getServiceId());
            if (old != null && !old.isEmpty()) secret = old;
        }
        try {
            UserCredentialStore.save(serviceId, displayName, url, method, secret);
        } catch (IllegalArgumentException error) {
            form.serviceId.setError(error.getMessage());
            return;
        }
        dialog.dismiss();
        reload();
        Notify.show(R.string.credential_saved);
    }

    private static int methodToIndex(UserCredentialMethod method) {
        if (method == null) return 0;
        switch (method) {
            case COOKIE: return 0;
            case OAUTH_TOKEN: return 1;
            case PASSWORD: return 2;
            default: return 0;
        }
    }

    private static UserCredentialMethod indexToMethod(int index) {
        switch (index) {
            case 1: return UserCredentialMethod.OAUTH_TOKEN;
            case 2: return UserCredentialMethod.PASSWORD;
            default: return UserCredentialMethod.COOKIE;
        }
    }

    private static final class ReloadTask implements Runnable {
        private final WeakReference<UserCredentialActivity> activityRef;

        ReloadTask(UserCredentialActivity activity) {
            activityRef = new WeakReference<>(activity);
        }

        @Override
        public void run() {
            List<OpenDriveUserCredential> credentials = UserCredentialStore.list();
            UserCredentialActivity activity = activityRef.get();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                UserCredentialActivity current = activityRef.get();
                if (!alive(current)) return;
                current.adapter.submit(credentials);
                current.binding.empty.setVisibility(credentials.isEmpty()
                        ? android.view.View.VISIBLE : android.view.View.GONE);
            });
        }
    }

    private static final class DeleteTask implements Runnable {
        private final WeakReference<UserCredentialActivity> activityRef;
        private final String serviceId;

        DeleteTask(UserCredentialActivity activity, String serviceId) {
            activityRef = new WeakReference<>(activity);
            this.serviceId = serviceId;
        }

        @Override
        public void run() {
            UserCredentialStore.delete(serviceId);
            UserCredentialActivity activity = activityRef.get();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                UserCredentialActivity current = activityRef.get();
                if (alive(current)) current.reload();
            });
        }
    }

    private void submit(Runnable runnable) {
        Future<?> future = Task.submitLarge(runnable);
        pendingTasks.removeIf(Future::isDone);
        pendingTasks.add(future);
        if (isDestroyed()) future.cancel(true);
    }

    private static boolean alive(UserCredentialActivity activity) {
        return activity != null && activity.binding != null && activity.adapter != null
                && !activity.isFinishing() && !activity.isDestroyed();
    }

    @Override
    protected void onDestroy() {
        for (Future<?> task : pendingTasks) task.cancel(true);
        pendingTasks.clear();
        if (activeDialog != null) {
            activeDialog.setOnDismissListener(null);
            activeDialog.dismiss();
            activeDialog = null;
        }
        if (binding != null) {
            binding.back.setOnClickListener(null);
            binding.add.setOnClickListener(null);
            binding.list.setAdapter(null);
        }
        if (adapter != null) adapter.release();
        adapter = null;
        binding = null;
        super.onDestroy();
    }
}
