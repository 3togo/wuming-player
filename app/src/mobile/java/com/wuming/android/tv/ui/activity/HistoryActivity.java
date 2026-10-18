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
import com.wuming.android.tv.bean.History;
import com.wuming.android.tv.databinding.ActivityHistoryBinding;
import com.wuming.android.tv.event.RefreshEvent;
import com.wuming.android.tv.library.UserLibrary;
import com.wuming.android.tv.ui.adapter.HistoryAdapter;
import com.wuming.android.tv.ui.base.BaseActivity;
import com.wuming.android.tv.ui.dialog.SyncDialog;
import com.wuming.android.tv.utils.Task;
import androidx.appcompat.app.AlertDialog;
import com.wuming.android.tv.utils.TvAlertDialogBuilder;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class HistoryActivity extends BaseActivity implements HistoryAdapter.OnClickListener {

    private static final String STATE_NEWEST_FIRST = "newestFirst";

    private ActivityHistoryBinding mBinding;
    private HistoryAdapter mAdapter;
    private AlertDialog deleteDialog;
    private boolean newestFirst = true;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, HistoryActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityHistoryBinding.inflate(getLayoutInflater());
    }

    @Override
    public void setSupportActionBar(@Nullable Toolbar toolbar) {
        super.setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        if (savedInstanceState != null) newestFirst = savedInstanceState.getBoolean(STATE_NEWEST_FIRST, true);
        setSupportActionBar(mBinding.toolbar);
        setRecyclerView();
        getHistory();
    }

    private void setRecyclerView() {
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.setLayoutManager(new GridLayoutManager(this, MobileProduct.getColumn(this)));
        mBinding.recycler.setAdapter(mAdapter = new HistoryAdapter(this));
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

    private void getHistory() {
        // Fetch history asynchronously to avoid main thread DB access
        Task.execute(() -> {
            List<History> items = sortHistory(UserLibrary.get().histories());
            postUi(() -> {
                ActivityHistoryBinding binding = mBinding;
                HistoryAdapter adapter = mAdapter;
                if (binding == null || adapter == null) return;
                adapter.setItems(items, (hasChange) -> {
                    if (mBinding != binding || mAdapter != adapter) return;
                    binding.progressLayout.showContent(true, adapter.getItemCount());
                    if (hasChange) binding.recycler.scrollToPosition(0);
                });
            });
        });
    }

    private List<History> sortHistory(List<History> items) {
        List<History> sorted = new ArrayList<>(items);
        Comparator<History> comparator = Comparator.comparingLong(History::getCreateTime);
        sorted.sort(newestFirst ? comparator.reversed() : comparator);
        return sorted;
    }

    private void toggleOrder() {
        newestFirst = !newestFirst;
        invalidateOptionsMenu();
        mAdapter.setItems(sortHistory(mAdapter.getItems()), () -> mBinding.recycler.scrollToPosition(0));
    }

    private void onSync() {
        SyncDialog.create().history().show(this);
    }

    private void onDelete() {
        if (mAdapter.isDelete()) {
            deleteDialog = new TvAlertDialogBuilder(this).setTitle(R.string.dialog_delete_record).setMessage(R.string.dialog_delete_history).setNegativeButton(R.string.dialog_negative, null).setPositiveButton(R.string.dialog_positive, (dialog, which) -> mAdapter.clear()).show();
        } else if (mAdapter.getItemCount() > 0) {
            mAdapter.setDelete(true);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (event.getType().equals(RefreshEvent.Type.HISTORY)) getHistory();
    }

    @Override
    public void onItemClick(History item) {
        VideoActivity.start(this, item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
    }

    @Override
    public void onItemDelete(History item) {
        HistoryAdapter adapter = mAdapter;
        adapter.remove(UserLibrary.get().deleteHistory(item), () -> {
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
        getMenuInflater().inflate(R.menu.menu_history, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem order = menu.findItem(R.id.order);
        if (order != null) order.setTitle(newestFirst ? R.string.history_oldest_first : R.string.history_newest_first);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) onBackInvoked();
        else if (item.getItemId() == R.id.order) toggleOrder();
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
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putBoolean(STATE_NEWEST_FIRST, newestFirst);
        super.onSaveInstanceState(outState);
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
