package com.wuming.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.wuming.android.tv.R;
import com.wuming.android.tv.cloud.opendrive.WebDavConnectorStore;
import com.wuming.android.tv.cloud.opendrive.WebDavConnectorFeature;
import com.wuming.android.tv.databinding.ActivityWebdavConnectorBinding;
import com.wuming.android.tv.databinding.DialogWebdavConnectorBinding;
import com.wuming.android.tv.ui.adapter.WebDavConnectorAdapter;
import com.wuming.android.tv.ui.base.BaseActivity;
import com.wuming.android.tv.utils.Notify;
import com.wuming.android.tv.utils.Task;
import com.wuming.android.tv.utils.TvAlertDialogBuilder;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/** Shared TV/mobile editor for OpenList and generic WebDAV connectors. */
public final class WebDavConnectorActivity extends BaseActivity
        implements WebDavConnectorAdapter.Listener {

    private ActivityWebdavConnectorBinding binding;
    private WebDavConnectorAdapter adapter;
    private final Set<Future<?>> pendingTasks = ConcurrentHashMap.newKeySet();
    private androidx.appcompat.app.AlertDialog activeDialog;

    public static void start(Activity activity) {
        if (!WebDavConnectorFeature.isEnabled()) return;
        activity.startActivity(new Intent(activity, WebDavConnectorActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = ActivityWebdavConnectorBinding.inflate(getLayoutInflater());
    }

    @Override
    protected boolean customWall() {
        return false;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        binding.back.setOnClickListener(view -> finish());
        binding.add.setOnClickListener(view -> showEditor(null));
        binding.add.setEnabled(WebDavConnectorFeature.isEnabled());
        binding.list.setLayoutManager(new LinearLayoutManager(this));
        binding.list.setAdapter(adapter = new WebDavConnectorAdapter(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!WebDavConnectorFeature.isEnabled()) {
            binding.empty.setText(R.string.webdav_connector_pending_detail);
            binding.empty.setVisibility(android.view.View.VISIBLE);
            return;
        }
        reload();
    }

    @Override
    public void onEdit(WebDavConnectorStore.Connector connector) {
        if (!WebDavConnectorFeature.isEnabled()) return;
        showEditor(connector);
    }

    @Override
    public void onDelete(WebDavConnectorStore.Connector connector) {
        if (!WebDavConnectorFeature.isEnabled()) return;
        new TvAlertDialogBuilder(this)
                .setTitle(R.string.webdav_connector_delete_title)
                .setMessage(getString(R.string.webdav_connector_delete_message, connector.name()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.webdav_connector_delete, (dialog, which) ->
                        submit(new DeleteTask(this, connector.id())))
                .show();
    }

    private void reload() {
        submit(new ReloadTask(this));
    }

    private void showEditor(WebDavConnectorStore.Connector connector) {
        if (activeDialog != null) activeDialog.dismiss();
        DialogWebdavConnectorBinding form = DialogWebdavConnectorBinding.inflate(
                LayoutInflater.from(this));
        form.password.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (connector != null) {
            form.name.setText(connector.name());
            form.url.setText(connector.baseUrl());
            form.path.setText(connector.initialPath());
            form.username.setText(connector.username());
            form.password.setHint(R.string.webdav_connector_password_unchanged);
        }
        androidx.appcompat.app.AlertDialog dialog = new TvAlertDialogBuilder(this)
                .setTitle(connector == null ? R.string.webdav_connector_add
                        : R.string.webdav_connector_edit)
                .setView(form.getRoot())
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.setOnShowListener(ignored -> {
            com.wuming.android.tv.utils.TvDialogFocus.form(dialog,
                    form.name, form.url, form.path, form.username, form.password);
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(view -> save(dialog, form, connector));
        });
        dialog.setOnDismissListener(ignored -> {
            if (activeDialog == dialog) activeDialog = null;
        });
        activeDialog = dialog;
        dialog.show();
    }

    private void save(androidx.appcompat.app.AlertDialog dialog,
                      DialogWebdavConnectorBinding form,
                      WebDavConnectorStore.Connector connector) {
        String name = form.name.getText().toString();
        String url = form.url.getText().toString();
        String path = form.path.getText().toString();
        String username = form.username.getText().toString();
        String password = form.password.getText().toString();
        try {
            WebDavConnectorStore.normalizeBaseUrl(url);
        } catch (IllegalArgumentException error) {
            form.url.setError(error.getMessage());
            return;
        }
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        SaveRequest request = new SaveRequest(connector == null ? null : connector.id(),
                name, url, path, username, password, connector != null);
        submit(new SaveTask(this, dialog, request));
    }

    private static final class ReloadTask implements Runnable {
        private final WeakReference<WebDavConnectorActivity> activityRef;

        ReloadTask(WebDavConnectorActivity activity) {
            activityRef = new WeakReference<>(activity);
        }

        @Override
        public void run() {
            List<WebDavConnectorStore.Connector> connectors = new WebDavConnectorStore().list();
            WebDavConnectorActivity activity = activityRef.get();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                WebDavConnectorActivity current = activityRef.get();
                if (!alive(current)) return;
                current.adapter.submit(connectors);
                current.binding.empty.setVisibility(connectors.isEmpty()
                        ? android.view.View.VISIBLE : android.view.View.GONE);
            });
        }
    }

    private static final class DeleteTask implements Runnable {
        private final WeakReference<WebDavConnectorActivity> activityRef;
        private final int connectorId;

        DeleteTask(WebDavConnectorActivity activity, int connectorId) {
            activityRef = new WeakReference<>(activity);
            this.connectorId = connectorId;
        }

        @Override
        public void run() {
            new WebDavConnectorStore().delete(connectorId);
            WebDavConnectorActivity activity = activityRef.get();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                WebDavConnectorActivity current = activityRef.get();
                if (alive(current)) current.reload();
            });
        }
    }

    private record SaveRequest(Integer id, String name, String url, String path,
                               String username, String password, boolean preservePassword) {
    }

    private static final class SaveTask implements Runnable {
        private final WeakReference<WebDavConnectorActivity> activityRef;
        private final WeakReference<androidx.appcompat.app.AlertDialog> dialogRef;
        private final SaveRequest request;

        SaveTask(WebDavConnectorActivity activity, androidx.appcompat.app.AlertDialog dialog,
                 SaveRequest request) {
            activityRef = new WeakReference<>(activity);
            dialogRef = new WeakReference<>(dialog);
            this.request = request;
        }

        @Override
        public void run() {
            String errorMessage = null;
            try {
                new WebDavConnectorStore().save(request.id(), request.name(), request.url(),
                        request.path(), request.username(), request.password(),
                        request.preservePassword());
            } catch (Exception error) {
                errorMessage = error.getMessage();
                if (errorMessage == null || errorMessage.isBlank()) errorMessage = "";
            }
            WebDavConnectorActivity activity = activityRef.get();
            if (activity == null) return;
            String result = errorMessage;
            activity.runOnUiThread(() -> deliverSaveResult(
                    activityRef, dialogRef, result));
        }
    }

    private static void deliverSaveResult(WeakReference<WebDavConnectorActivity> activityRef,
                                          WeakReference<androidx.appcompat.app.AlertDialog> dialogRef,
                                          String errorMessage) {
        WebDavConnectorActivity activity = activityRef.get();
        androidx.appcompat.app.AlertDialog dialog = dialogRef.get();
        if (!alive(activity) || dialog == null || !dialog.isShowing()) return;
        if (errorMessage == null) {
            dialog.dismiss();
            activity.reload();
            Notify.show(R.string.webdav_connector_saved);
            return;
        }
        android.widget.Button positive = dialog.getButton(
                androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
        if (positive != null) positive.setEnabled(true);
        Notify.show(errorMessage.isEmpty()
                ? activity.getString(R.string.webdav_connector_save_failed) : errorMessage);
    }

    private void submit(Runnable runnable) {
        Future<?> future = Task.submitLarge(runnable);
        pendingTasks.removeIf(Future::isDone);
        pendingTasks.add(future);
        if (isDestroyed()) future.cancel(true);
    }

    private static boolean alive(WebDavConnectorActivity activity) {
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
