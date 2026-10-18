package com.wuming.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.wuming.android.tv.MobileProduct;
import com.wuming.android.tv.R;
import com.wuming.android.tv.api.config.VodConfig;
import com.wuming.android.tv.bean.Config;
import com.wuming.android.tv.bean.Keep;
import com.wuming.android.tv.databinding.ActivityKeepBinding;
import com.wuming.android.tv.event.RefreshEvent;
import com.wuming.android.tv.impl.Callback;
import com.wuming.android.tv.library.UserLibrary;
import com.wuming.android.tv.ui.adapter.MobileKeepAdapter;
import com.wuming.android.tv.ui.base.BaseActivity;
import com.wuming.android.tv.ui.dialog.SyncDialog;
import com.wuming.android.tv.utils.Notify;
import androidx.appcompat.app.AlertDialog;
import com.wuming.android.tv.utils.TvAlertDialogBuilder;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class MobileKeepActivity extends BaseActivity implements MobileKeepAdapter.OnClickListener {

    private ActivityKeepBinding mBinding;
    private MobileKeepAdapter mAdapter;
    private AlertDialog deleteDialog;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, MobileKeepActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityKeepBinding.inflate(getLayoutInflater());
    }

    @Override
    public void setSupportActionBar(@Nullable Toolbar toolbar) {
        super.setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setSupportActionBar(mBinding.toolbar);
        setRecyclerView();
        getKeep();
    }

    private void setRecyclerView() {
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.setLayoutManager(new GridLayoutManager(this, MobileProduct.getColumn(this)));
        mBinding.recycler.setAdapter(mAdapter = new MobileKeepAdapter(this));
        mAdapter.setSize(MobileProduct.getSpec(this));
        mBinding.recycler.post(this::updateGridGeometry);
    }

    private void updateGridGeometry() {
        if (mBinding == null || mAdapter == null) return;
        if (!(mBinding.recycler.getLayoutManager() instanceof GridLayoutManager manager)) return;
        int viewportWidth = mBinding.recycler.getWidth()
                - mBinding.recycler.getPaddingStart()
                - mBinding.recycler.getPaddingEnd();
        if (viewportWidth <= 0) return;
        mAdapter.setSize(MobileProduct.getSpecForViewport(this, viewportWidth, manager.getSpanCount()));
    }

    private void getKeep() {
        ActivityKeepBinding binding = mBinding;
        MobileKeepAdapter adapter = mAdapter;
        adapter.setItems(UserLibrary.get().videoFavorites(), () -> {
            if (mBinding == binding && mAdapter == adapter && binding != null) binding.progressLayout.showContent(true, adapter.getItemCount());
        });
    }

    private void onSync() {
        SyncDialog.create().keep().show(this);
    }

    private void onDelete() {
        if (mAdapter.isDelete()) {
            deleteDialog = new TvAlertDialogBuilder(this).setTitle(R.string.dialog_delete_record).setMessage(R.string.dialog_delete_keep).setNegativeButton(R.string.dialog_negative, null).setPositiveButton(R.string.dialog_positive, (dialog, which) -> mAdapter.clear()).show();
        } else if (mAdapter.getItemCount() > 0) {
            mAdapter.setDelete(true);
        }
    }

    private void loadConfig(Config config, Keep item) {
        VodConfig.load(config, lifecycleCallback(new Callback() {
            @Override
            public void success() {
                VideoActivity.start(getActivity(), item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
            }
        }));
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (event.getType().equals(RefreshEvent.Type.KEEP)) getKeep();
    }

    @Override
    public void onItemClick(Keep item) {
        // Config.find reads from in-memory cache (no main-thread DB access)
        Config config = Config.find(item.getCid());
        if (config == null) MobileFastSearchActivity.start(this, item.getVodName());
        else if (item.getCid() != VodConfig.getCid()) loadConfig(config, item);
        else VideoActivity.start(this, item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
    }

    @Override
    public void onItemDelete(Keep item) {
        MobileKeepAdapter adapter = mAdapter;
        adapter.remove(UserLibrary.get().deleteFavorite(item), () -> {
            if (mAdapter == adapter && adapter.getItemCount() == 0) adapter.setDelete(false);
        });
    }

    @Override
    public boolean onLongClick() {
        mAdapter.setDelete(!mAdapter.isDelete());
        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_keep, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) onBackInvoked();
        else if (item.getItemId() == R.id.delete) onDelete();
        else if (item.getItemId() == R.id.sync) onSync();
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onBackInvoked() {
        if (mAdapter.isDelete()) mAdapter.setDelete(false);
        else super.onBackInvoked();
    }

    @Override
    protected void onOrientationChanged(Configuration newConfig) {
        ((GridLayoutManager) mBinding.recycler.getLayoutManager()).setSpanCount(MobileProduct.getColumn(this));
        mBinding.recycler.post(this::updateGridGeometry);
    }

    @Override
    protected void onDestroy() {
        if (deleteDialog != null && deleteDialog.isShowing()) deleteDialog.dismiss();
        deleteDialog = null;
        mBinding = null;
        mAdapter = null;
        super.onDestroy();
    }
}
