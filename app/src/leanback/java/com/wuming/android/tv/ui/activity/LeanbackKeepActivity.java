package com.wuming.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.viewbinding.ViewBinding;

import com.wuming.android.tv.api.config.VodConfig;
import com.wuming.android.tv.bean.Config;
import com.wuming.android.tv.bean.Keep;
import com.wuming.android.tv.databinding.ActivityKeepBinding;
import com.wuming.android.tv.event.RefreshEvent;
import com.wuming.android.tv.impl.Callback;
import com.wuming.android.tv.library.UserLibrary;
import com.wuming.android.tv.ui.adapter.LeanbackKeepAdapter;
import com.wuming.android.tv.ui.base.BaseActivity;
import com.wuming.android.tv.ui.custom.GridFocusRestorer;
import com.wuming.android.tv.ui.custom.PosterGridSetup;
import com.wuming.android.tv.utils.Notify;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class LeanbackKeepActivity extends BaseActivity implements LeanbackKeepAdapter.OnClickListener {

    private static final String STATE_FOCUS_PREFIX = "keepFocus";

    private ActivityKeepBinding mBinding;
    private LeanbackKeepAdapter mAdapter;
    private GridFocusRestorer.ItemFocus mRestoredFocus;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, LeanbackKeepActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityKeepBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mRestoredFocus = GridFocusRestorer.readItemFocus(savedInstanceState, STATE_FOCUS_PREFIX);
        setRecyclerView();
        getKeep();
    }

    private void setRecyclerView() {
        PosterGridSetup.configure(mBinding.recycler, mAdapter = new LeanbackKeepAdapter(this));
    }

    private void getKeep() {
        GridFocusRestorer.ItemFocus focus = mRestoredFocus != null ? mRestoredFocus :
                GridFocusRestorer.captureItem(
                        mBinding.recycler, position -> mAdapter.getItem(position).getKey());
        mRestoredFocus = null;
        mAdapter.setItems(UserLibrary.get().videoFavorites(),
                () -> {
                    mBinding.progressLayout.showContent(true, mAdapter.getItemCount());
                    GridFocusRestorer.restoreItemAfterLayout(
                            mBinding.recycler, focus, position -> mAdapter.getItem(position).getKey());
                });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        GridFocusRestorer.saveItemFocus(outState, STATE_FOCUS_PREFIX,
                GridFocusRestorer.captureItem(
                        mBinding.recycler, position -> mAdapter.getItem(position).getKey()));
        super.onSaveInstanceState(outState);
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
        if (event.getType() == RefreshEvent.Type.KEEP) getKeep();
    }

    @Override
    public void onItemClick(Keep item) {
        // Config.find reads from in-memory cache (no main-thread DB access)
        Config config = Config.find(item.getCid());
        if (config == null) CollectActivity.start(this, item.getVodName());
        else if (item.getCid() != VodConfig.getCid()) loadConfig(config, item);
        else VideoActivity.start(this, item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
    }

    @Override
    public void onItemDelete(Keep item) {
        GridFocusRestorer.ItemFocus focus = GridFocusRestorer.captureItem(
                mBinding.recycler, position -> mAdapter.getItem(position).getKey());
        mAdapter.remove(UserLibrary.get().deleteFavorite(item), () -> {
            if (mAdapter.getItemCount() == 0) mAdapter.setDelete(false);
            GridFocusRestorer.restoreItemAfterLayout(
                    mBinding.recycler, focus, position -> mAdapter.getItem(position).getKey());
        });
    }

    @Override
    public boolean onLongClick() {
        mAdapter.setDelete(true);
        return true;
    }

    @Override
    protected void onBackInvoked() {
        if (mAdapter.isDelete()) mAdapter.setDelete(false);
        else super.onBackInvoked();
    }

    @Override
    protected void onDestroy() {
        mBinding = null;
        mAdapter = null;
        super.onDestroy();
    }
}
