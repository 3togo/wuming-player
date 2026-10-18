package com.wuming.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.viewbinding.ViewBinding;

import com.wuming.android.tv.R;
import com.wuming.android.tv.bean.Class;
import com.wuming.android.tv.bean.Result;
import com.wuming.android.tv.databinding.ActivityFolderBinding;
import com.wuming.android.tv.ui.base.BaseActivity;
import com.wuming.android.tv.ui.fragment.MobileFolderFragment;
import com.wuming.android.tv.utils.BundleUtil;

public class FolderActivity extends BaseActivity {

    private static final String FOLDER_FRAGMENT_TAG = "folder_fragment";

    private ActivityFolderBinding mBinding;

    public static void start(Activity activity, String key, Result result) {
        if (result == null || result.getTypes().isEmpty()) return;
        Intent intent = new Intent(activity, FolderActivity.class);
        intent.putExtra("key", key);
        intent.putExtra("result", result);
        activity.startActivity(intent);
    }

    private String getKey() {
        return getIntent().getStringExtra("key");
    }

    private Result getResult() {
        return BundleUtil.getParcelableExtra(getIntent(), "result", Result.class);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityFolderBinding.inflate(getLayoutInflater());
    }

    @Override
    public void setSupportActionBar(@Nullable Toolbar toolbar) {
        super.setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setSupportActionBar(mBinding.toolbar);
        Class type = getResult().getTypes().get(0);
        setTitle(type.getTypeName());
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.container, MobileFolderFragment.newInstance(getKey(), type, 8), FOLDER_FRAGMENT_TAG)
                .commit();
    }

    @Nullable
    private MobileFolderFragment getFragment() {
        return (MobileFolderFragment) getSupportFragmentManager().findFragmentByTag(FOLDER_FRAGMENT_TAG);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) onBackInvoked();
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onBackInvoked() {
        MobileFolderFragment fragment = getFragment();
        if (fragment != null && fragment.canBack()) fragment.goBack();
        else super.onBackInvoked();
    }

    @Override
    protected void onDestroy() {
        mBinding = null;
        super.onDestroy();
    }
}
