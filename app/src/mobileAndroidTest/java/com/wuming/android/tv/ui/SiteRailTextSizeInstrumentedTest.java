package com.wuming.android.tv.ui;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.wuming.android.tv.ui.adapter.BaseFastListAdapter;
import com.wuming.android.tv.ui.adapter.MobileFastListAdapter;
import com.wuming.android.tv.utils.LayoutConfig;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;

/**
 * Smoke test for the Fast Search left column (vertical site rail).
 *
 * Regression guard for the inconsistent-text-size bug: the rail's first row is the
 * short "全部" label and later rows carry longer ranked site names, so any per-label
 * shrink-to-fit made neighbouring rows render at different sizes. Every row must use
 * the single LayoutConfig.getMobileSearchSiteTextSizePixels() token instead.
 */
@RunWith(AndroidJUnit4.class)
public class SiteRailTextSizeInstrumentedTest {

    @Test
    public void siteRailRowsUseUniformTextSize() {
        Context context = ApplicationProvider.getApplicationContext();

        MobileFastListAdapter adapter = new MobileFastListAdapter();
        adapter.setSiteListLayout(0);
        adapter.setNewData(Collections.singletonList("全部"));
        adapter.addData("这是一个很长很长的站点名称示例", null);

        FrameLayout parent = new FrameLayout(context);
        BaseFastListAdapter.ViewHolder shortRow = bind(adapter, parent, 0);
        BaseFastListAdapter.ViewHolder longRow = bind(adapter, parent, 1);

        float token = LayoutConfig.getMobileSearchSiteTextSizePixels(context);
        assertEquals("short row must use the shared rail text size", token, shortRow.tvSearchSite.getTextSize(), 1.0f);
        assertEquals("long row must use the same rail text size", token, longRow.tvSearchSite.getTextSize(), 1.0f);
        assertEquals("rows must not differ in text size",
                shortRow.tvSearchSite.getTextSize(), longRow.tvSearchSite.getTextSize(), 0.01f);
    }

    @NonNull
    private static BaseFastListAdapter.ViewHolder bind(
            MobileFastListAdapter adapter,
            FrameLayout parent,
            int position) {
        BaseFastListAdapter.ViewHolder holder = adapter.createViewHolder(parent, adapter.getItemViewType(position));
        adapter.bindViewHolder(holder, position);
        return holder;
    }
}
