package com.wuming.android.tv.ui.adapter;

import com.wuming.android.tv.BuildConfig;
import com.wuming.android.tv.bean.Site;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class SiteDialogSelectionTest {
    @SuppressWarnings("unchecked")
    private BaseSelectAdapter<Site, ?> adapter() throws Exception {
        String name = BuildConfig.FLAVOR.startsWith("mobile") ? "MobileSiteAdapter" : "LeanbackSiteAdapter";
        Class<?> type = Class.forName("com.wuming.android.tv.ui.adapter." + name);
        Class<?> listener = Class.forName(type.getName() + "$OnClickListener");
        return (BaseSelectAdapter<Site, ?>) type.getConstructor(listener).newInstance(new Object[]{null});
    }

    @Test
    public void selectsActiveSiteInDisplayedOrderAfterFilteringAndRanking() throws Exception {
        Site first = Site.get("first", "First");
        Site hidden = Site.get("hidden", "Hidden");
        Site active = Site.get("active", "Active");
        active.setActivated(true);
        Site faster = Site.get("faster", "Faster");
        List<Site> configured = List.of(first, hidden, active, faster);
        BaseSelectAdapter<Site, ?> adapter = adapter();
        adapter.getItems().clear();
        adapter.getItems().addAll(List.of(first, faster, active));

        assertEquals(2, adapter.getPosition());
        assertSame(active, adapter.getActivated());
        adapter.getItems().clear();
        adapter.getItems().addAll(List.of(first, active, faster));
        assertEquals(1, adapter.getPosition());
        assertEquals(2, configured.indexOf(active));
    }

    @Test
    public void missingOrHiddenActiveSiteFallsBackToFirstDisplayedSite() throws Exception {
        Site first = Site.get("first", "First");
        BaseSelectAdapter<Site, ?> adapter = adapter();
        adapter.getItems().clear();
        adapter.getItems().add(first);
        assertEquals(0, adapter.getPosition());
        assertSame(first, adapter.getActivated());
        adapter.getItems().clear();
        assertEquals(0, adapter.getPosition());
    }
}
