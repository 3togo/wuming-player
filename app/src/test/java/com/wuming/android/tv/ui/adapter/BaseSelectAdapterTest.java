package com.wuming.android.tv.ui.adapter;

import static org.junit.Assert.*;

import android.view.ViewGroup;
import androidx.recyclerview.widget.RecyclerView;
import org.junit.Test;
import java.util.List;

/** Java subclasses must retain selection and live-list behavior after Kotlin migration. */
public class BaseSelectAdapterTest {
    @Test public void emptyAndUnselectedListsKeepTheirDistinctFallbacks() {
        TestAdapter adapter = new TestAdapter();
        assertEquals(0, adapter.getPosition());
        assertNull(adapter.getActivated());
        adapter.getItems().addAll(List.of("first", "second"));
        assertEquals("first", adapter.getActivated());
        adapter.getItems().clear();
        assertNull(adapter.getActivated());
    }

    @Test public void javaSelectionOverrideAndExposedListRemainEffective() {
        TestAdapter adapter = new TestAdapter();
        adapter.getItems().addAll(List.of("first", "selected", "last"));
        assertEquals(1, adapter.getPosition());
        assertEquals("selected", adapter.getActivated());
        adapter.getItems().remove("first");
        assertEquals(0, adapter.getPosition());
        assertEquals(2, adapter.getItemCount());
        assertEquals("selected", adapter.getActivated());
    }

    private static final class TestAdapter extends BaseSelectAdapter<String, RecyclerView.ViewHolder> {
        @Override public void addAll(List<String> items) { super.addAll(items); }
        @Override protected boolean isActivated(String item) { return "selected".equals(item); }
        @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            throw new UnsupportedOperationException();
        }
        @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {}
    }
}
