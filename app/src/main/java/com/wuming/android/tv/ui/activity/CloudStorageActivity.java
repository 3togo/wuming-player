package com.wuming.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.wuming.android.tv.R;
import com.wuming.android.tv.cloud.CloudStorageProvider;
import com.wuming.tv.core.cloud.OpenDriveAccountSnapshot;
import com.wuming.android.tv.cloud.opendrive.OpenDriveTokenManager;
import com.wuming.android.tv.cloud.opendrive.AccountCredentialBackup;
import com.wuming.tv.core.cloud.CredentialBackupFiles;
import com.wuming.tv.core.cloud.LegacyCredentialBackupAction;
import com.wuming.tv.core.cloud.OpenDriveTokenState;
import com.wuming.android.tv.cloud.opendrive.WebDavConnectorFeature;
import com.wuming.android.tv.databinding.ActivityCloudStorageBinding;
import com.wuming.android.tv.ui.adapter.OpenDriveAccountAdapter;
import com.wuming.android.tv.ui.base.BaseActivity;
import com.wuming.android.tv.ui.dialog.OpenDriveLoginDialog;
import com.wuming.android.tv.ui.dialog.BilibiliLoginDialog;
import com.wuming.android.tv.utils.Notify;
import com.wuming.android.tv.utils.FileChooser;
import com.wuming.android.tv.utils.Util;
import com.wuming.android.tv.utils.PermissionUtil;
import com.wuming.android.tv.utils.ResUtil;
import com.wuming.android.tv.utils.Task;
import com.wuming.android.tv.utils.ViewportScale;
import java.io.File;
import com.github.catvod.net.PersistentCookieJar;
import com.github.catvod.utils.Path;
import com.wuming.android.tv.utils.TvAlertDialogBuilder;

import java.lang.ref.WeakReference;
import java.util.Arrays;

/** MDrive-style account/login hub for providers built into OpenDrive. */
public class CloudStorageActivity extends BaseActivity implements OpenDriveAccountAdapter.Listener {

    private static final String BILIBILI_URL = "https://www.bilibili.com/";
    private static final String DEFAULT_BACKUP_PASSPHRASE = "12345678";

    private ActivityCloudStorageBinding binding;
    private OpenDriveAccountAdapter adapter;
    private OpenDriveTokenManager tokens;
    private OpenDriveLoginDialog loginDialog;
    private BilibiliLoginDialog bilibiliLoginDialog;
    private int refreshGeneration;
    private android.net.Uri pendingExportUri;
    private androidx.appcompat.app.AlertDialog passphraseDialog;
    private android.net.Uri pendingImportUri;

    private final ActivityResultLauncher<String> exportLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/json") {
                @Override
                public Intent createIntent(android.content.Context context, String input) {
                    return super.createIntent(context, input).putExtra(
                            DocumentsContract.EXTRA_INITIAL_URI, credentialBackupDocumentUri());
                }
            }, uri -> {
                if (uri == null) return;
                pendingExportUri = uri;
                showExportPassphrase();
            });

    private final ActivityResultLauncher<String[]> importLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument() {
                @Override
                public Intent createIntent(android.content.Context context, String[] input) {
                    return super.createIntent(context, input).putExtra(
                            DocumentsContract.EXTRA_INITIAL_URI, credentialBackupDocumentUri());
                }
            }, uri -> {
                if (uri == null) return;
                pendingImportUri = uri;
                showImportPassphrase();
            });

    private final ActivityResultLauncher<Intent> tvImportLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null
                        || result.getData().getData() == null) return;
                pendingImportUri = result.getData().getData();
                showImportPassphrase();
            });

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, CloudStorageActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = ActivityCloudStorageBinding.inflate(getLayoutInflater());
    }

    public float getSizeInDp() {
        return !Util.isLeanback() && ResUtil.isPad()
                ? ViewportScale.baseWidthDp()
                : ViewportScale.MOBILE_SHORT_EDGE_DESIGN_DP;
    }

    @Override
    protected boolean customWall() {
        return false;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        tokens = OpenDriveTokenManager.get();
        binding.back.setOnClickListener(view -> finish());
        binding.cache.setOnClickListener(view -> CloudStorageCacheActivity.start(this));
        binding.bilibiliAccount.setVisibility(android.view.View.VISIBLE);
        binding.bilibiliLogin.setOnClickListener(view -> startBilibiliLogin(false));
        binding.bilibiliAuto.setOnClickListener(view -> startBilibiliLogin(true));
        binding.bilibiliState.setOnClickListener(view -> onBilibiliLogout());
        OpenDriveAccountAdapter.applyFocusStyle(binding.bilibiliAuto, R.color.blue_500);
        binding.exportCredentials.setOnClickListener(view -> {
            String filename = AccountCredentialBackup.suggestFilename();
            if (Util.isLeanback()) showCredentialPath(true, filename);
            else exportLauncher.launch(filename);
        });
        binding.importCredentials.setOnClickListener(view -> {
            if (Util.isLeanback()) {
                PermissionUtil.requestFile(this, granted -> {
                    if (!granted || !alive(this) || !prepareCredentialDirectory()) return;
                    migrateLegacyCredentialFiles();
                    FileChooser.from(tvImportLauncher).show(credentialBackupDirectory(), ".json");
                });
            } else {
                migrateLegacyCredentialFiles();
                importLauncher.launch(new String[]{"application/json", "application/octet-stream"});
            }
        });
        binding.connectors.setOnClickListener(view -> WebDavConnectorActivity.start(this));
        binding.connectors.setEnabled(WebDavConnectorFeature.isEnabled());
        if (!WebDavConnectorFeature.isEnabled()) {
            binding.connectors.setText(R.string.webdav_connector_pending);
        }
        binding.credentials.setOnClickListener(view -> UserCredentialActivity.start(this));
        binding.list.setLayoutManager(new LinearLayoutManager(this));
        binding.list.setAdapter(adapter = new OpenDriveAccountAdapter(tokens, this));
        if (savedInstanceState != null) {
            String export = savedInstanceState.getString("credentialExportUri");
            String restore = savedInstanceState.getString("credentialImportUri");
            if (export != null) {
                pendingExportUri = android.net.Uri.parse(export);
                showExportPassphrase();
            } else if (restore != null) {
                pendingImportUri = android.net.Uri.parse(restore);
                showImportPassphrase();
            }
        }
    }

    private File credentialBackupDirectory() {
        // Scoped storage hides shell/USB-copied JSON in arbitrary shared folders on
        // Android 11+. The TV uses a filesystem picker, so keep its backups in
        // app-owned storage; phones continue to use the document provider.
        if (Util.isLeanback() && android.os.Build.VERSION.SDK_INT >= 30) {
            File external = getExternalFilesDir(null);
            return new File(external != null ? external : getFilesDir(), CredentialBackupFiles.DIRECTORY_NAME);
        }
        return new File(legacyCredentialBackupDirectory(), CredentialBackupFiles.DIRECTORY_NAME);
    }

    private File legacyCredentialBackupDirectory() {
        return new File(Path.root(), getString(R.string.backup_storage_name));
    }

    private android.net.Uri credentialBackupDocumentUri() {
        // The platform's primary storage document ID uses a path relative to Path.root().
        // Supply the same location to both SAF contracts without requiring broad file access.
        String relative = Path.root().toPath().relativize(credentialBackupDirectory().toPath()).toString();
        return DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:" + relative);
    }

    private boolean prepareCredentialDirectory() {
        File directory = credentialBackupDirectory();
        if (directory.isDirectory() || directory.mkdirs() || directory.isDirectory()) return true;
        Notify.show(R.string.cloud_credentials_directory_unavailable);
        return false;
    }

    /** Directories older releases wrote credential backups to, oldest naming first. */
    private java.util.List<File> legacyCredentialDirectories() {
        java.util.LinkedHashSet<File> directories = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        names.add(getString(R.string.backup_storage_name));
        String legacyName = getString(R.string.legacy_app_name);
        if (!names.contains(legacyName)) names.add(legacyName);
        for (String name : names) {
            File base = new File(Path.root(), name);
            directories.add(base);
            directories.add(new File(base, CredentialBackupFiles.DIRECTORY_NAME));
        }
        return new java.util.ArrayList<>(directories);
    }

    private void migrateLegacyCredentialFiles() {
        File target = credentialBackupDirectory();
        if (!target.isDirectory() && !target.mkdirs()) return;
        // A backup too small to be a valid copy is discarded wherever it lives: the target
        // directory and every legacy directory alike.
        purgeTooSmall(target);
        for (File legacy : legacyCredentialDirectories()) {
            if (legacy.equals(target) || !legacy.isDirectory()) continue;
            purgeTooSmall(legacy);
            File[] backups = legacy.listFiles((dir, name) -> CredentialBackupFiles.isBackupFileName(name));
            if (backups == null || backups.length == 0) continue;
            for (File file : backups) {
                File dest = new File(target, file.getName());
                // Shared policy: relocate only while the name is still free in the target, otherwise
                // discard the legacy copy rather than overwrite or rename the one already held there.
                if (CredentialBackupFiles.relocation(file.length(), dest.exists()) == LegacyCredentialBackupAction.MOVE) {
                    file.renameTo(dest);
                } else {
                    file.delete();
                }
            }
        }
    }

    private void purgeTooSmall(File dir) {
        if (!dir.isDirectory()) return;
        File[] backups = dir.listFiles((d, name) -> CredentialBackupFiles.isBackupFileName(name));
        if (backups == null) return;
        for (File file : backups) {
            if (CredentialBackupFiles.isTooSmall(file.length())) {
                // Too small to be a valid backup: always discard the truncated or corrupt copy,
                // regardless of which directory it lives in.
                file.delete();
            }
        }
    }

    private void showCredentialPath(boolean exporting, String filename) {
        PermissionUtil.requestFile(this, granted -> {
            if (!granted || !alive(this) || !prepareCredentialDirectory()) return;
            LinearLayout fields = new LinearLayout(this);
            fields.setOrientation(LinearLayout.VERTICAL);
            int padding = getResources().getDimensionPixelSize(R.dimen.vs_24);
            fields.setPadding(padding, 0, padding, 0);
            android.widget.TextView hint = new android.widget.TextView(this);
            hint.setText(R.string.cloud_credentials_path_hint);
            hint.setTextColor(getColor(R.color.white_80));
            fields.addView(hint);
            EditText path = passwordField(R.string.cloud_credentials_path);
            path.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            path.setText(new File(credentialBackupDirectory(), filename).getPath());
            fields.addView(path);
            androidx.appcompat.app.AlertDialog dialog = new TvAlertDialogBuilder(this, R.style.DarkDialogStyle)
                    .setTitle(exporting ? R.string.cloud_credentials_export_title : R.string.cloud_credentials_import_title)
                    .setView(fields)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok, null)
                    .create();
            dialog.setOnShowListener(ignored -> {
                com.wuming.android.tv.utils.TvDialogFocus.form(dialog, path);
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    File file;
                    try {
                        file = AccountCredentialBackup.resolve(path.getText().toString());
                        path.setText(file.getPath());
                        File parent = file.getParentFile();
                        if (exporting && credentialBackupDirectory().equals(parent)
                                && !parent.isDirectory() && !parent.mkdirs()) {
                            path.setError(getString(R.string.cloud_credentials_path_invalid));
                            return;
                        }
                        if (exporting ? file.exists() || file.getParentFile() == null
                                || !file.getParentFile().isDirectory() : !file.isFile()) {
                            path.setError(getString(R.string.cloud_credentials_path_invalid));
                            return;
                        }
                    } catch (IllegalArgumentException e) {
                        path.setError(getString(R.string.cloud_credentials_path_invalid));
                        return;
                    }
                    dialog.dismiss();
                    if (exporting) {
                        pendingExportUri = android.net.Uri.fromFile(file);
                        showExportPassphrase();
                    } else {
                        pendingImportUri = android.net.Uri.fromFile(file);
                        showImportPassphrase();
                    }
                });
            });
            passphraseDialog = dialog;
            dialog.show();
        });
    }

    private void showExportPassphrase() {
        LinearLayout fields = passphraseFields(true);
        EditText password = (EditText) fields.getChildAt(1);
        android.widget.CheckBox reveal = (android.widget.CheckBox) fields.getChildAt(2);
        androidx.appcompat.app.AlertDialog dialog = new TvAlertDialogBuilder(this, R.style.DarkDialogStyle)
                .setTitle(R.string.cloud_credentials_export_title)
                .setView(fields)
                .setNegativeButton(android.R.string.cancel, (ignored, which) -> pendingExportUri = null)
                .setNeutralButton(R.string.cloud_credentials_clear_password, null)
                .setPositiveButton(R.string.cloud_credentials_export, null)
                .create();
        dialog.setOnShowListener(ignored -> {
            com.wuming.android.tv.utils.TvDialogFocus.form(dialog, password);
            configureClearPassword(dialog, password);
            wireRevealToggle(dialog, password, reveal);
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    String value = password.getText().toString();
                    if (value.length() < 8) {
                        password.setError(getString(R.string.cloud_credentials_passphrase_short));
                    } else {
                        android.net.Uri uri = pendingExportUri;
                        pendingExportUri = null;
                        char[] passphrase = value.toCharArray();
                        password.getText().clear();
                        dialog.dismiss();
                        Task.submitLarge(() -> exportCredentials(uri, passphrase));
                    }
                });
        });
        dialog.setOnCancelListener(ignored -> pendingExportUri = null);
        dialog.setOnDismissListener(ignored -> password.getText().clear());
        passphraseDialog = dialog;
        dialog.show();
    }

    private void showImportPassphrase() {
        LinearLayout fields = passphraseFields(false);
        EditText password = (EditText) fields.getChildAt(1);
        android.widget.CheckBox reveal = (android.widget.CheckBox) fields.getChildAt(2);
        androidx.appcompat.app.AlertDialog dialog = new TvAlertDialogBuilder(this, R.style.DarkDialogStyle)
                .setTitle(R.string.cloud_credentials_import_title)
                .setView(fields)
                .setNegativeButton(android.R.string.cancel, (ignored, which) -> pendingImportUri = null)
                .setNeutralButton(R.string.cloud_credentials_clear_password, null)
                .setPositiveButton(R.string.cloud_credentials_import, null)
                .create();
        dialog.setOnShowListener(ignored -> {
            com.wuming.android.tv.utils.TvDialogFocus.form(dialog, password);
            configureClearPassword(dialog, password);
            wireRevealToggle(dialog, password, reveal);
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    String value = password.getText().toString();
                    if (value.length() < 8) {
                        password.setError(getString(R.string.cloud_credentials_passphrase_short));
                        return;
                    }
                    android.net.Uri uri = pendingImportUri;
                    pendingImportUri = null;
                    char[] passphrase = value.toCharArray();
                    password.getText().clear();
                    dialog.dismiss();
                    Task.submitLarge(() -> importCredentials(uri, passphrase));
                });
        });
        dialog.setOnCancelListener(ignored -> pendingImportUri = null);
        dialog.setOnDismissListener(ignored -> password.getText().clear());
        passphraseDialog = dialog;
        dialog.show();
    }

    private void configureClearPassword(androidx.appcompat.app.AlertDialog dialog, EditText... fields) {
        android.widget.Button clear = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL);
        clear.setOnClickListener(view -> {
            for (EditText field : fields) {
                field.getText().clear();
                field.setError(null);
            }
            fields[0].requestFocus();
        });
        if (Util.isLeanback()) {
            android.widget.Button cancel = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE);
            cancel.setNextFocusLeftId(clear.getId());
            clear.setNextFocusRightId(cancel.getId());
            clear.setNextFocusUpId(fields[fields.length - 1].getId());
            clear.setNextFocusDownId(fields[0].getId());
        }
    }

    private LinearLayout passphraseFields(boolean export) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = getResources().getDimensionPixelSize(R.dimen.vs_24);
        layout.setPadding(padding, 0, padding, 0);
        android.widget.TextView warning = new android.widget.TextView(this);
        warning.setText(export ? R.string.cloud_credentials_export_warning
                : R.string.cloud_credentials_import_warning);
        warning.setTextColor(getColor(R.color.white_80));
        layout.addView(warning, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        EditText password = passwordField(R.string.cloud_credentials_passphrase);
        if (export) password.setText(DEFAULT_BACKUP_PASSPHRASE);
        layout.addView(password);
        // The passphrase is visible by default so the user can read what the remote types;
        // the toggle hides it again. Reading it back replaces the old double-entry check.
        android.widget.CheckBox reveal = new android.widget.CheckBox(this);
        reveal.setText(R.string.cloud_credentials_show_password);
        reveal.setChecked(true);
        reveal.setSaveEnabled(false);
        layout.addView(reveal, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        applyPasswordVisibility(password, reveal);
        reveal.setOnCheckedChangeListener((button, checked) -> applyPasswordVisibility(password, reveal));
        return layout;
    }

    private void applyPasswordVisibility(EditText password, android.widget.CheckBox reveal) {
        int selection = password.getSelectionEnd();
        int type = reveal.isChecked()
                ? InputType.TYPE_CLASS_TEXT
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
        password.setInputType(type);
        password.setSelection(Math.min(selection, password.getText().length()));
    }

    /**
     * Keeps the reveal toggle inside the dialog's remote focus loop: field -> toggle ->
     * positive button, with the toggle reachable upward from the button row.
     */
    private void wireRevealToggle(androidx.appcompat.app.AlertDialog dialog,
                                  EditText password, android.widget.CheckBox reveal) {
        if (!Util.isLeanback()) return;
        if (reveal.getId() == View.NO_ID) reveal.setId(View.generateViewId());
        if (password.getId() == View.NO_ID) password.setId(View.generateViewId());
        android.widget.Button positive = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
        android.widget.Button negative = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE);
        reveal.setFocusable(true);
        reveal.setFocusableInTouchMode(true);
        reveal.setNextFocusUpId(password.getId());
        reveal.setNextFocusDownId(positive.getId());
        password.setNextFocusDownId(reveal.getId());
        positive.setNextFocusUpId(reveal.getId());
        negative.setNextFocusUpId(reveal.getId());
        android.view.View.OnKeyListener remote = (view, keyCode, event) -> {
            if (keyCode != android.view.KeyEvent.KEYCODE_DPAD_UP
                    && keyCode != android.view.KeyEvent.KEYCODE_DPAD_DOWN) return false;
            if (event.getAction() == android.view.KeyEvent.ACTION_UP) return true;
            android.view.View target = view.focusSearch(
                    keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP
                            ? android.view.View.FOCUS_UP : android.view.View.FOCUS_DOWN);
            return target != null && target != view && target.requestFocus();
        };
        reveal.setOnKeyListener(remote);
    }

    private EditText passwordField(int hint) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setSingleLine(true);
        field.setSaveEnabled(false);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setTextColor(getColor(R.color.white));
        field.setHintTextColor(getColor(R.color.white_60));
        field.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return field;
    }

    private void exportCredentials(android.net.Uri uri, char[] passphrase) {
        int exported = -1;
        try {
            exported = AccountCredentialBackup.exportTo(getContentResolver(), uri, passphrase);
        } catch (Exception ignored) {
            clear(passphrase);
        }
        final int count = exported;
        runOnUiThread(() -> {
            if (!alive(this)) return;
            if (count < 0) Notify.show(R.string.cloud_credentials_export_failed);
            else if (count == 0) Notify.show(R.string.cloud_credentials_none);
            else Notify.show(getString(R.string.cloud_credentials_exported, count));
        });
    }

    private void importCredentials(android.net.Uri uri, char[] passphrase) {
        int imported = -1;
        int failureMessage = R.string.cloud_credentials_import_failed;
        try {
            imported = AccountCredentialBackup.importFrom(getContentResolver(), uri, passphrase);
        } catch (AccountCredentialBackup.WrongPassphraseException error) {
            failureMessage = R.string.cloud_credentials_import_wrong_passphrase;
        } catch (AccountCredentialBackup.EmptyBackupException error) {
            failureMessage = R.string.cloud_credentials_import_empty;
        } catch (AccountCredentialBackup.MalformedBackupException error) {
            failureMessage = R.string.cloud_credentials_import_invalid;
        } catch (Exception ignored) {
        } finally {
            clear(passphrase);
        }
        final int count = imported;
        final int message = failureMessage;
        runOnUiThread(() -> {
            if (!alive(this)) return;
            if (count < 0) {
                Notify.show(message);
                return;
            }
            refreshBilibiliAccount();
            refreshAccounts();
            if (count == 0) Notify.show(R.string.cloud_credentials_none);
            else Notify.show(getString(R.string.cloud_credentials_imported, count));
        });
    }

    private static void clear(char[] value) {
        if (value != null) Arrays.fill(value, '\0');
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshBilibiliAccount();
        refreshAccounts();
    }

    private void startBilibiliLogin(boolean assisted) {
        if (bilibiliLoginDialog != null) bilibiliLoginDialog.dismiss();
        bilibiliLoginDialog = BilibiliLoginDialog.show(this, () -> {
            bilibiliLoginDialog = null;
            refreshBilibiliAccount();
            Notify.show(R.string.open_drive_status_logged_in);
        }, () -> bilibiliLoginDialog = null, assisted);
    }

    private void refreshBilibiliAccount() {
        if (binding == null) return;
        boolean loggedIn = PersistentCookieJar.get().hasLoginCookies(BILIBILI_URL);
        String name = getString(R.string.bilibili_provider_name);
        binding.bilibiliLogin.setText(getString(R.string.open_drive_provider_status, name,
                getString(loggedIn ? R.string.open_drive_status_logged_in
                        : R.string.open_drive_status_not_logged_in)));
        OpenDriveAccountAdapter.applyFocusStyle(binding.bilibiliLogin, loggedIn
                ? R.color.cloud_login_connected : R.color.cloud_login_disconnected);
        binding.bilibiliAuto.setVisibility(loggedIn ? android.view.View.GONE : android.view.View.VISIBLE);
        binding.bilibiliState.setText(loggedIn
                ? R.string.open_drive_logout : R.string.open_drive_inactive);
        binding.bilibiliState.setEnabled(loggedIn);
        OpenDriveAccountAdapter.applyFocusStyle(binding.bilibiliState, loggedIn
                ? R.color.cloud_login_disconnected : R.color.white_20);
    }

    private void onBilibiliLogout() {
        if (!PersistentCookieJar.get().hasLoginCookies(BILIBILI_URL)) return;
        String name = getString(R.string.bilibili_provider_name);
        new TvAlertDialogBuilder(this, R.style.DarkDialogStyle)
                .setTitle(getString(R.string.open_drive_logout_title, name))
                .setMessage(R.string.bilibili_logout_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.open_drive_logout, (ignored, which) -> {
                    PersistentCookieJar.get().clear("bilibili.com");
                    PersistentCookieJar.get().clearWebView(BILIBILI_URL);
                    refreshBilibiliAccount();
                    Notify.show(getString(R.string.open_drive_logout_complete, name));
                })
                .show();
    }

    @Override
    public void onLogin(CloudStorageProvider provider, boolean assisted) {
        OpenDriveAccountSnapshot account = tokens.snapshot(provider.getId());
        if (!account.canStartLogin()) {
            CloudStorageWebActivity.startManagement(this, provider);
            return;
        }
        if (loginDialog != null) loginDialog.dismiss();
        loginDialog = OpenDriveLoginDialog.show(this, provider, assisted,
                this::refreshAccounts);
    }

    @Override
    public void onLogout(String providerId, String displayName) {
        new TvAlertDialogBuilder(this, R.style.DarkDialogStyle)
                .setTitle(getString(R.string.open_drive_logout_title, displayName))
                .setMessage(R.string.open_drive_logout_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.open_drive_logout,
                        (ignored, which) -> Task.submitLarge(
                                new LogoutTask(this, providerId, displayName)))
                .show();
    }

    private void refreshAccounts() {
        if (adapter == null || tokens == null) return;
        adapter.refresh();
        int generation = ++refreshGeneration;
        OpenDriveTokenManager manager = tokens;
        Task.submitLarge(() -> {
            for (OpenDriveAccountSnapshot account : manager.accounts()) {
                if (account.state() == OpenDriveTokenState.PRESENT
                        || account.state() == OpenDriveTokenState.ERROR) {
                    manager.verify(account.providerId());
                }
            }
            runOnUiThread(() -> {
                if (generation == refreshGeneration && alive(this) && adapter != null) {
                    adapter.refresh();
                }
            });
        });
    }

    private static final class LogoutTask implements Runnable {
        private final WeakReference<CloudStorageActivity> activityRef;
        private final String providerId;
        private final String displayName;

        LogoutTask(CloudStorageActivity activity, String providerId, String displayName) {
            activityRef = new WeakReference<>(activity);
            this.providerId = providerId;
            this.displayName = displayName;
        }

        @Override
        public void run() {
            boolean success = true;
            try {
                OpenDriveTokenManager.get().reset(providerId);
            } catch (Exception error) {
                success = false;
            }
            CloudStorageActivity activity = activityRef.get();
            if (activity == null) return;
            boolean result = success;
            activity.runOnUiThread(() -> {
                CloudStorageActivity current = activityRef.get();
                if (!alive(current)) return;
                current.adapter.refresh();
                Notify.show(current.getString(result
                        ? R.string.open_drive_logout_complete
                        : R.string.open_drive_logout_failed, displayName));
            });
        }
    }

    private static boolean alive(CloudStorageActivity activity) {
        return activity != null && activity.binding != null
                && !activity.isFinishing() && !activity.isDestroyed();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (pendingExportUri != null) state.putString("credentialExportUri", pendingExportUri.toString());
        if (pendingImportUri != null) state.putString("credentialImportUri", pendingImportUri.toString());
    }

    @Override
    protected void onDestroy() {
        if (binding != null) {
            binding.back.setOnClickListener(null);
            binding.cache.setOnClickListener(null);
            binding.bilibiliLogin.setOnClickListener(null);
            binding.bilibiliAuto.setOnClickListener(null);
            binding.bilibiliState.setOnClickListener(null);
            binding.exportCredentials.setOnClickListener(null);
            binding.importCredentials.setOnClickListener(null);
            binding.connectors.setOnClickListener(null);
            binding.credentials.setOnClickListener(null);
            binding.list.setAdapter(null);
        }
        adapter = null;
        if (loginDialog != null) loginDialog.dismiss();
        loginDialog = null;
        if (bilibiliLoginDialog != null) bilibiliLoginDialog.dismiss();
        bilibiliLoginDialog = null;
        refreshGeneration++;
        tokens = null;
        if (passphraseDialog != null) passphraseDialog.dismiss();
        passphraseDialog = null;
        pendingExportUri = null;
        pendingImportUri = null;
        binding = null;
        super.onDestroy();
    }
}
