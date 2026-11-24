package com.wuming.android.tv.ui.base;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.viewbinding.ViewBinding;

import com.wuming.android.tv.R;
import com.wuming.android.tv.databinding.ActivityFileBinding;
import com.wuming.android.tv.ui.adapter.FileAdapter;
import com.wuming.android.tv.utils.FileChooser;
import com.wuming.android.tv.utils.Notify;
import com.wuming.android.tv.utils.PermissionUtil;
import com.github.catvod.utils.Path;

import java.io.File;
import java.lang.ref.WeakReference;

/** Shared navigation and lifetime; flavors supply toolbar, spacing and focus behavior. */
public abstract class BaseFileActivity extends BaseActivity implements FileAdapter.OnClickListener {
    protected ActivityFileBinding mBinding;
    private FileAdapter adapter;
    private FileBrowserController browser;

    @Override
    protected final ViewBinding getBinding() {
        return mBinding = ActivityFileBinding.inflate(getLayoutInflater());
    }

    @Override
    protected final void initView(Bundle savedInstanceState) {
        String extensionFilter = getIntent().getStringExtra(FileChooser.EXTRA_EXTENSION_FILTER);
        boolean newestFirst = getIntent().getBooleanExtra(FileChooser.EXTRA_NEWEST_FIRST, false);
        boolean deletable = getIntent().getBooleanExtra(FileChooser.EXTRA_ALLOW_DELETE, false);
        String rootDirectory = getIntent().getStringExtra(FileChooser.EXTRA_ROOT_DIRECTORY);
        browser = new FileBrowserController(rootDirectory == null ? Path.root() : new File(rootDirectory),
                extensionFilter, newestFirst);
        configureBrowser();
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.setAdapter(adapter = new FileAdapter(this, deletable, this::confirmDelete));
        WeakReference<BaseFileActivity> owner = new WeakReference<>(this);
        PermissionUtil.requestFile(this, allGranted -> {
            BaseFileActivity activity = owner.get();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()
                    || activity.browser == null) return;
            activity.update(activity.browser.openInitial(
                    activity.getIntent().getStringExtra(FileChooser.EXTRA_INITIAL_DIRECTORY)));
        });
    }

    private void confirmDelete(File file) {
        new com.wuming.android.tv.utils.TvAlertDialogBuilder(this)
                .setTitle(R.string.file_delete)
                .setMessage(getString(R.string.file_delete_message, file.getName()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.file_delete, (dialog, which) -> {
                    if (file.delete()) update(browser.current());
                    else Notify.show(R.string.file_delete_failed);
                })
                .show();
    }

    protected abstract void configureBrowser();

    protected abstract void resetBrowserPosition();

    protected void onDirectoryChanged(File directory) {
    }

    private void update(File directory) {
        File current = browser.open(directory);
        resetBrowserPosition();
        adapter.addAll(browser.entries());
        onDirectoryChanged(current);
        mBinding.progressLayout.showContent(true, adapter.getItemCount());
    }

    @Override
    public final void onItemClick(File file) {
        if (browser == null || isFinishing() || isDestroyed()) return;
        if (file.isDirectory()) {
            update(file);
        } else {
            setResult(RESULT_OK, new Intent().setData(Uri.fromFile(file)));
            finish();
        }
    }

    @Override
    protected final void onBackInvoked() {
        if (browser == null || !browser.canGoBack()) super.onBackInvoked();
        else update(browser.goBack());
    }

    @Override
    protected void onDestroy() {
        mBinding = null;
        adapter = null;
        browser = null;
        super.onDestroy();
    }
}
