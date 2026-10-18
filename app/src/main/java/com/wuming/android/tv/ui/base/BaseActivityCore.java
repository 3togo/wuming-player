package com.wuming.android.tv.ui.base;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.wuming.android.tv.ui.custom.CustomWallView;
import com.wuming.android.tv.utils.TextScale;
import com.wuming.android.tv.utils.LifecycleCallback;
import com.wuming.android.tv.impl.Callback;
import com.wuming.android.tv.utils.UiTaskScope;
import com.wuming.android.tv.utils.ViewCleanup;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

/**
 * Shared base activity containing logic common to both leanback and mobile variants.
 * Each variant extends this and adds its own platform-specific behavior.
 *
 * Template methods:
 *   - {@link #onPostCreate()} — called after common onCreate logic, for variant-specific init
 */
public abstract class BaseActivityCore extends AppCompatActivity {

    private final UiTaskScope uiTasks = new UiTaskScope();

    protected final void postUi(Runnable task) {
        postUi(task, 0);
    }

    protected final void postUi(Runnable task, long delayMillis) {
        uiTasks.post(task, delayMillis);
    }

    protected final Callback lifecycleCallback(Callback callback) {
        return LifecycleCallback.wrap(this, callback);
    }

    protected final Callback repeatedLifecycleCallback(Callback callback) {
        return LifecycleCallback.wrapRepeated(this, callback);
    }

    protected abstract ViewBinding getBinding();

    protected abstract void onPostCreate();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Inflate once. Variant implementations retain this binding; invoking
        // getBinding() again configures a detached second hierarchy while the
        // first, uninitialized hierarchy remains on screen.
        ViewBinding binding = getBinding();
        setContentView(binding.getRoot());
        binding.getRoot().post(() -> TextScale.apply(binding.getRoot()));
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this);
        }
        initView(savedInstanceState);
        setBackCallback();
        initEvent();
        onPostCreate();
    }

    @Override
    public void setContentView(View view) {
        super.setContentView(view);
        if (!customWall()) return;
        ViewGroup content = findViewById(android.R.id.content);
        // Remove existing CustomWallView to prevent accumulation on recreation
        if (content.getChildCount() > 0 && content.getChildAt(0) instanceof CustomWallView) {
            content.removeViewAt(0);
        }
        content.addView(new CustomWallView(this, null), 0,
                new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
    }

    protected FragmentActivity getActivity() {
        return this;
    }

    protected boolean customWall() {
        return true;
    }

    protected void initView(Bundle savedInstanceState) {
    }

    protected void initEvent() {
    }

    protected boolean isVisible(View view) {
        return view.getVisibility() == View.VISIBLE;
    }

    protected boolean isGone(View view) {
        return view.getVisibility() == View.GONE;
    }

    private void setBackCallback() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                onBackInvoked();
            }
        });
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onSubscribe(Object o) {
    }

    protected void onBackInvoked() {
        finish();
    }

    protected void startHomeAsRoot() {
        if (!isTaskRoot()) return;
        Intent intent = new Intent()
                .setClassName(getPackageName(), "com.wuming.android.tv.ui.activity.HomeActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    @Override
    protected void onDestroy() {
        uiTasks.close();
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this);
        }
        super.onDestroy();
        ViewCleanup.release(findViewById(android.R.id.content));
    }
}
