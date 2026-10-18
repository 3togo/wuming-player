package com.wuming.android.tv.ui.activity;

import android.widget.FrameLayout;

import androidx.viewbinding.ViewBinding;

import com.wuming.android.tv.ui.base.BaseActivityCore;

/** Isolated lifecycle host; never included in debug/release applications. */
public final class MemoryTestActivity extends BaseActivityCore implements com.wuming.android.tv.ui.dialog.InfoDialog.Listener {
    @Override public void onShare(CharSequence title) { }
    @Override protected ViewBinding getBinding() {
        FrameLayout root = new FrameLayout(this);
        root.setId(android.R.id.custom);
        return () -> root;
    }
    @Override protected void onPostCreate() { }
    @Override protected boolean customWall() { return false; }
}
