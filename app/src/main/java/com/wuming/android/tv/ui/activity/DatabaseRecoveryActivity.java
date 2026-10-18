package com.wuming.android.tv.ui.activity;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import com.wuming.android.tv.R;
import java.io.File;
import java.io.OutputStream;

/** Independent process: recovery must never query Room or load the normal home screen. */
public class DatabaseRecoveryActivity extends Activity {
    public static void open(Context context, boolean schema) {
        // Reuse the recovery screen, but allow reopening after Back or a denied background
        // launch. A process-local "opened once" flag cannot track the separate UI process.
        context.startActivity(new Intent(context, DatabaseRecoveryActivity.class)
                .putExtra("schema", schema).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    protected File databaseFile() { return new File(getFilesDir(), "TV/database/tv"); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_database_recovery);
        ((TextView) findViewById(R.id.recovery_message)).setText(getIntent().getBooleanExtra("schema", false)
                ? R.string.database_recovery_schema : R.string.database_recovery_storage);
        findViewById(R.id.recovery_export).setOnClickListener(view -> startActivityForResult(
                new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/zip").putExtra(Intent.EXTRA_TITLE, "wuming-database-recovery.zip"), 1));
        findViewById(R.id.recovery_retry).setOnClickListener(view -> com.wuming.android.tv.utils.Task.execute(() -> {
            try {
                com.wuming.tv.core.library.DatabaseUpgradePolicy.requireSupported(
                        databaseFile(), 30, com.wuming.android.tv.db.AppDatabase.VERSION);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
                    if (launch != null) startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK));
                    finish();
                });
            } catch (java.io.IOException error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    ((TextView) findViewById(R.id.recovery_status)).setText(
                            error instanceof com.wuming.tv.core.library.DatabaseUpgradePolicy.UnsupportedVersion
                                    ? R.string.database_recovery_schema : R.string.database_recovery_storage);
                });
            }
        }));
        findViewById(R.id.recovery_export).requestFocus();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 1 || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        findViewById(R.id.recovery_export).setEnabled(false);
        com.wuming.android.tv.utils.Task.execute(() -> {
            boolean success = false;
            try (OutputStream raw = getContentResolver().openOutputStream(uri)) {
                com.wuming.tv.core.library.DatabaseRecoveryArchive.write(databaseFile(),
                        java.util.Objects.requireNonNull(raw));
                success = true;
            } catch (Exception ignored) { success = false; /* Never log private database content. */ }
            final boolean exported = success;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                findViewById(R.id.recovery_export).setEnabled(true);
                ((TextView) findViewById(R.id.recovery_status)).setText(exported
                        ? R.string.database_recovery_exported : R.string.database_recovery_failed);
            });
        });
    }
}
