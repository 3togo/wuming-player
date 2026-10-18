package com.wuming.android.tv.ui.base;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.viewbinding.ViewBinding;

import com.wuming.android.tv.utils.TextScale;
import com.wuming.android.tv.utils.LifecycleCallback;
import com.wuming.android.tv.impl.Callback;
import com.wuming.android.tv.utils.ViewCleanup;
import com.wuming.android.tv.utils.UiTaskScope;

public abstract class BaseFragment extends Fragment {

    private boolean isViewCreated;
    private boolean isDataLoaded;
    private UiTaskScope viewTasks;

    /** Main-thread callbacks belong to this view incarnation, not the retained fragment. */
    protected final void postUi(Runnable task) {
        postUi(task, 0);
    }

    protected final void postUi(Runnable task, long delayMillis) {
        if (viewTasks != null) viewTasks.post(task, delayMillis);
    }

    protected final void removeUi(Runnable task) {
        if (viewTasks != null) viewTasks.remove(task);
    }

    protected final Callback lifecycleCallback(Callback callback) {
        return LifecycleCallback.wrap(getViewLifecycleOwner(), callback);
    }

    protected final Callback repeatedLifecycleCallback(Callback callback) {
        return LifecycleCallback.wrapRepeated(getViewLifecycleOwner(), callback);
    }

    protected abstract ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container);

    protected abstract void releaseBinding();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = getBinding(inflater, container).getRoot();
        TextScale.apply(root);
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewTasks = new UiTaskScope();
        isViewCreated = true;
        if (isResumed()) {
            lazyLoad();
            isDataLoaded = true;
        }
    }

    private void lazyLoad() {
        initMenu();
        initView();
        initEvent();
    }

    protected void initMenu() {
    }

    protected void initView() {
    }

    protected void initEvent() {
    }

    public boolean canBack() {
        return true;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (isViewCreated && !isDataLoaded) {
            lazyLoad();
            isDataLoaded = true;
        }
    }

    @Override
    public void onDestroyView() {
        if (viewTasks != null) viewTasks.close();
        viewTasks = null;
        View root = getView();
        super.onDestroyView();
        ViewCleanup.release(root);
        releaseBinding();
        isViewCreated = false;
        isDataLoaded = false;
    }
}
