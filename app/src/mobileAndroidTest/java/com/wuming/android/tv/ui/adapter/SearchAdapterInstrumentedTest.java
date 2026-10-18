package com.wuming.android.tv.ui.adapter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.wuming.android.tv.bean.Site;
import com.wuming.android.tv.bean.Vod;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@RunWith(AndroidJUnit4.class)
public class SearchAdapterInstrumentedTest {
    @Test
    public void providerEntriesSurviveRepeatedBatchesAndSiteSwitchCallbacks() throws Exception {
        AtomicReference<SearchAdapter> ref = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                ref.set(new SearchAdapter(null, item -> {})));
        SearchAdapter adapter = ref.get();
        Vod first = candidate("audit-a", "1");
        Vod second = candidate("audit-b", "1");
        commit(done -> adapter.setItems(List.of(first, second), done));
        assertEquals(2, adapter.getItemCount());
        commit(done -> adapter.addAll(List.of(candidate("audit-a", "1")), done));
        assertEquals(2, adapter.getItemCount());
        // This overload is used when the user selects another site in MobileCollectFragment.
        commit(done -> adapter.setItems(List.of(second), done));
        commit(done -> adapter.addAll(List.of(candidate("audit-b", "2")), done));
        assertEquals(2, adapter.getItemCount());
        assertTrue(adapter.getItems().stream().allMatch(v -> "audit-b".equals(v.getSiteKey())));
    }

    private static void commit(Consumer<Runnable> submit) throws Exception {
        CountDownLatch committed = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> submit.accept(committed::countDown));
        assertTrue("List commit timed out", committed.await(20, TimeUnit.SECONDS));
    }

    private static Vod candidate(String site, String id) {
        Vod vod = new Vod();
        vod.setSite(Site.get(site, site));
        vod.setId(id);
        vod.setName("Same title");
        return vod;
    }
}
