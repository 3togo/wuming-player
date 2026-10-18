package com.wuming.android.tv.utils;

import static org.junit.Assert.*;

import android.view.View;
import android.view.ViewGroup;

import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.Presenter;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class LeanbackAdapterLifecycleInstrumentedTest {
    @Test
    public void teardownDetachesBothRecyclerAndObjectAdapterObservers() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ArrayObjectAdapter data = new ArrayObjectAdapter(new Presenter() {
                @Override public ViewHolder onCreateViewHolder(ViewGroup parent) { return new ViewHolder(new View(parent.getContext())); }
                @Override public void onBindViewHolder(ViewHolder holder, Object item) { }
                @Override public void onUnbindViewHolder(ViewHolder holder) { }
            });
            for (int i = 0; i < 10; i++) {
                RecyclerView view = new RecyclerView(ApplicationProvider.getApplicationContext());
                ItemBridgeAdapter bridge = new ItemBridgeAdapter(data);
                view.setAdapter(bridge);
                assertTrue(bridge.hasObservers());
                ViewCleanup.release(view);
                assertNull(view.getAdapter());
                assertEquals(0, bridge.getItemCount());
                assertFalse(bridge.hasObservers());
                data.add("after teardown");
                assertEquals(0, bridge.getItemCount());
            }
        });
    }
}
