package com.wuming.android.tv.ui.adapter;

import static org.junit.Assert.assertEquals;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.wuming.android.tv.bean.Config;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class ConfigAdapterInstrumentedTest {

    @Test
    public void removeAcceptsUnmodifiableConfigSnapshot() {
        Config item = new Config() {
            @Override
            public void delete() {
                // The adapter behavior is under test; avoid touching persistent app data.
            }
        };
        ConfigAdapter adapter = new ConfigAdapter(new NoOpListener())
                .setItems(Collections.unmodifiableList(List.of(item)));

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                assertEquals(0, adapter.remove(item)));

        assertEquals(0, adapter.getItemCount());
    }

    private static final class NoOpListener implements ConfigAdapter.OnClickListener {

        @Override
        public void onTextClick(Config item) {
        }

        @Override
        public void onDeleteClick(Config item) {
        }
    }
}
