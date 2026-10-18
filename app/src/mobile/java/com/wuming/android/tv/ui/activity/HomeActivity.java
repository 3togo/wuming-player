package com.wuming.android.tv.ui.activity;

import android.app.PendingIntent;
import android.app.SearchManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;
import androidx.core.splashscreen.SplashScreen;
import androidx.fragment.app.Fragment;
import androidx.viewbinding.ViewBinding;

import com.wuming.android.tv.R;
import com.wuming.android.tv.Updater;
import com.wuming.android.tv.api.config.LiveConfig;
import com.wuming.android.tv.api.config.ConfigurationStartup;
import com.wuming.android.tv.bean.BeanUtils;
import com.wuming.android.tv.bean.Config;
import com.wuming.android.tv.databinding.ActivityHomeBinding;
import com.wuming.android.tv.db.AppDatabase;
import com.wuming.android.tv.event.ConfigEvent;
import com.wuming.android.tv.event.RefreshEvent;
import com.wuming.android.tv.event.ServerEvent;
import com.wuming.android.tv.event.StateEvent;
import com.wuming.android.tv.impl.Callback;
import com.wuming.android.tv.player.Source;
import com.wuming.android.tv.receiver.ShortcutReceiver;
import com.wuming.android.tv.server.Server;
import com.wuming.android.tv.service.PlaybackService;
import com.wuming.android.tv.ui.base.BaseActivity;
import com.wuming.android.tv.ui.custom.FragmentStateManager;
import com.wuming.android.tv.ui.fragment.SettingFragment;
import com.wuming.android.tv.ui.fragment.SettingPlayerFragment;
import com.wuming.android.tv.ui.fragment.VodFragment;
import com.wuming.android.tv.utils.FileChooser;
import com.wuming.android.tv.utils.LayoutConfig;
import com.wuming.android.tv.utils.NavigationGestureExclusion;
import com.wuming.android.tv.utils.Notify;
import com.wuming.android.tv.utils.PermissionUtil;
import com.wuming.android.tv.utils.ResUtil;
import com.wuming.android.tv.utils.Task;
import com.wuming.android.tv.utils.UrlUtil;
import com.github.catvod.net.OkHttp;
import com.google.android.material.navigation.NavigationBarView;

import com.google.android.material.navigationrail.NavigationRailView;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class HomeActivity extends BaseActivity implements NavigationBarView.OnItemSelectedListener {

    private FragmentStateManager mManager;
    private ActivityHomeBinding mBinding;

    private NavigationBarView getNav() {
        return (NavigationBarView) mBinding.navigation;
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityHomeBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        checkAction(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        initFragment(savedInstanceState);
        PermissionUtil.requestLocalNetwork(this, granted -> {
            initConfig();
        });
    }

    @Override
    protected void onPostCreate() {
        Updater.create().start(this);
    }

    @Override
    protected void initEvent() {
        setupNavigation();
        setNavigation();
    }

    private void setupNavigation() {
        configureNavigationRail();
        getNav().setOnItemSelectedListener(this);
        mBinding.navigation.findViewById(R.id.live).setOnLongClickListener(this::addShortcut);
        NavigationGestureExclusion.apply(mBinding.navigation, !ResUtil.isLand(this));
    }

    private void configureNavigationRail() {
        if (!ResUtil.isLand(this)) return;
        NavigationBarView navigation = getNav();
        int railWidth = LayoutConfig.getMobileNavigationRailWidthPixels(this);
        ViewGroup.LayoutParams params = navigation.getLayoutParams();
        params.width = railWidth;
        navigation.setMinimumWidth(0);
        navigation.setLayoutParams(params);
        navigation.setItemIconSize(
                Math.round(railWidth * LayoutConfig.MOBILE_NAVIGATION_ICON_SIZE_RATIO));
        navigation.setItemActiveIndicatorWidth(
                Math.round(railWidth * LayoutConfig.MOBILE_NAVIGATION_INDICATOR_WIDTH_RATIO));
        navigation.setItemActiveIndicatorHeight(
                Math.round(railWidth * LayoutConfig.MOBILE_NAVIGATION_INDICATOR_HEIGHT_RATIO));
        navigation.setItemActiveIndicatorMarginHorizontal(
                Math.round(railWidth * (1f - LayoutConfig.MOBILE_NAVIGATION_INDICATOR_WIDTH_RATIO) / 2f));
        navigation.setItemTextAppearanceActive(R.style.NavigationRailLabel);
        navigation.setItemTextAppearanceInactive(R.style.NavigationRailLabel);
        int navigationTextSize = LayoutConfig.getMobileNavigationTextSizePixels(this);
        setNavigationTextSize(navigation, navigationTextSize);
        navigation.addOnLayoutChangeListener((view, left, top, right, bottom,
                                               oldLeft, oldTop, oldRight, oldBottom) ->
                setNavigationTextSize(view, navigationTextSize));
        navigation.setItemPaddingTop(0);
        navigation.setItemPaddingBottom(0);
        if (navigation instanceof NavigationRailView rail) {
            rail.setMenuGravity(Gravity.CENTER_VERTICAL);
            rail.setItemMinimumHeight(LayoutConfig.getMobileNavigationItemHeightPixels(this));
            rail.setItemSpacing(0);
        }
    }

    private void setNavigationTextSize(View view, int textSize) {
        if (view instanceof TextView textView) {
            textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize);
        } else if (view instanceof ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                setNavigationTextSize(group.getChildAt(index), textSize);
            }
        }
    }

    private void checkAction(Intent intent) {
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            VideoActivity.push(this, intent.getStringExtra(Intent.EXTRA_TEXT));
        } else if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            PermissionUtil.requestFile(this, allGranted -> checkType(intent));
        } else if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            String keyword = intent.getStringExtra(SearchManager.QUERY);
            if (BeanUtils.isNotEmpty(keyword)) MobileFastSearchActivity.start(this, keyword);
        }
    }

    private void checkType(Intent intent) {
        if ("text/plain".equals(intent.getType()) || UrlUtil.path(intent.getData()).endsWith(".m3u")) {
            FileChooser.importFile(this, intent.getData(), path -> loadLive("file:/" + path));
        } else {
            VideoActivity.push(this, intent.getData().toString());
        }
    }

    private void initFragment(Bundle savedInstanceState) {
        initFragment(savedInstanceState, 0);
    }

    private void initFragment(Bundle savedInstanceState, int position) {
        mManager = new FragmentStateManager(mBinding.container, getSupportFragmentManager()) {
            @Override
            public Fragment getItem(int position) {
                if (position == 0) return VodFragment.newInstance();
                if (position == 1) return SettingFragment.newInstance();
                if (position == 2) return SettingPlayerFragment.newInstance();
                return null;
            }
        };
        if (savedInstanceState == null) change(position);
    }

    private void initConfig() {
        ConfigurationStartup.load(getCallback());
    }

    private Callback getCallback() {
        return lifecycleCallback(new Callback() {
            @Override
            public void success() {
                checkAction(getIntent());
            }

            @Override
            public void error(String msg) {
                checkAction(getIntent());
                StateEvent.empty();
                Notify.show(msg);
            }
        });
    }

    private void loadLive(String url) {
        Task.execute(() -> {
            Config config = Config.find(url, 1);
            runOnUiThread(() -> LiveConfig.load(config, lifecycleCallback(new Callback() {
                @Override
                public void success() {
                    openLive();
                }
            })));
        });
    }

    private void setNavigation() {
        getNav().getMenu().findItem(R.id.vod).setVisible(true);
        getNav().getMenu().findItem(R.id.setting).setVisible(true);
        getNav().getMenu().findItem(R.id.live).setVisible(LiveConfig.hasLoadedUrl());
        if (mManager != null) {
            selectNavigationSilently(getCurrentFragmentPosition() == 0 ? R.id.vod : R.id.setting);
        }
    }

    private void selectNavigationSilently(int itemId) {
        getNav().setOnItemSelectedListener(null);
        getNav().setSelectedItemId(itemId);
        getNav().setOnItemSelectedListener(this);
    }

    private boolean openLive() {
        MobileLiveActivity.start(this);
        return false;
    }

    private boolean addShortcut(View view) {
        ShortcutInfoCompat info = new ShortcutInfoCompat.Builder(this, getString(R.string.nav_live)).setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher)).setIntent(new Intent(Intent.ACTION_VIEW, null, this, MobileLiveActivity.class)).setShortLabel(getString(R.string.nav_live)).build();
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, new Intent(this, ShortcutReceiver.class).setAction(ShortcutReceiver.ACTION), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        ShortcutManagerCompat.requestPinShortcut(this, info, pendingIntent.getIntentSender());
        return true;
    }

    public void change(int position) {
        mManager.change(position);
        int itemId = position == 0 ? R.id.vod : R.id.setting;
        if (getNav().getMenu().findItem(itemId).isVisible()) {
            selectNavigationSilently(itemId);
        }
        if (position == 0 && mManager.getFragment(0) instanceof VodFragment fragment) {
            fragment.applyResponsiveLayout();
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        switch (event.type()) {
            case VOD:
                RefreshEvent.home();
                break;
            case COMMON:
            case LIVE:
                setNavigation();
                break;
            case BOOT:
                MobileLiveActivity.start(this);
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onServerEvent(ServerEvent event) {
        if (event.type() == ServerEvent.Type.PUSH) VideoActivity.push(this, event.text());
        if (event.type() == ServerEvent.Type.SEARCH) MobileFastSearchActivity.start(this, event.text());
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        if (getNav().getSelectedItemId() == item.getItemId()) return false;
        if (item.getItemId() == R.id.setting) {
            return mManager.change(1);
        }
        if (item.getItemId() == R.id.vod) return mManager.change(0);
        if (item.getItemId() == R.id.live) return openLive();
        return false;
    }

    @Override
    protected void onOrientationChanged(Configuration newConfig) {
        int currentPosition = mManager != null ? getCurrentFragmentPosition() : 0;

        // Fragment views belong to the old container. Detach them before
        // replacing the hierarchy so change() can attach them to the new one.
        if (mManager != null) mManager.detachAll();

        mBinding = ActivityHomeBinding.inflate(getLayoutInflater());
        setContentView(mBinding.getRoot());
        setupNavigation();
        setNavigation();
        initFragment(null, currentPosition);
    }

    private int getCurrentFragmentPosition() {
        if (mManager.isVisible(2)) return 2;
        if (mManager.isVisible(1)) return 1;
        return 0;
    }

    @Override
    protected void onBackInvoked() {
        if (!getNav().getMenu().findItem(R.id.vod).isVisible()) {
            setNavigation();
        } else if (mManager.isVisible(2)) {
            change(1);
        } else if (mManager.isVisible(1)) {
            getNav().setSelectedItemId(R.id.vod);
        } else if (mManager.canBack(0)) {
            if (PlaybackService.isRunning()) moveTaskToBack(true);
            else super.onBackInvoked();
        }
    }

    @Override
    protected void onDestroy() {
        AppDatabase.backup();
        OkHttp.get().clear();
        Source.get().exit();
        Server.get().stop();
        mBinding = null;
        mManager = null;
        super.onDestroy();
    }
}
