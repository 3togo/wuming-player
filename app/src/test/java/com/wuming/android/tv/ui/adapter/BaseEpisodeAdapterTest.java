package com.wuming.android.tv.ui.adapter;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import android.view.ViewGroup;

import androidx.annotation.NonNull;

import com.wuming.android.tv.bean.Episode;
import com.wuming.android.tv.ui.base.BaseEpisodeHolder;

import org.junit.Test;

import java.util.ArrayList;

public class BaseEpisodeAdapterTest {

    @Test
    public void getActivatedReturnsEmptyEpisodeWhenNoItemIsActivated() {
        Episode first = new Episode();
        Episode second = new Episode();
        ArrayList<Episode> episodes = new ArrayList<>();
        episodes.add(first);
        episodes.add(second);

        Episode activated = new TestAdapter(episodes).getActivated();

        assertNotSame(first, activated);
        assertNotSame(second, activated);
    }

    @Test
    public void getActivatedReturnsActivatedItem() {
        Episode first = new Episode();
        Episode second = new Episode();
        second.setActivated(true);
        ArrayList<Episode> episodes = new ArrayList<>();
        episodes.add(first);
        episodes.add(second);

        assertSame(second, new TestAdapter(episodes).getActivated());
    }

    private static final class TestAdapter extends BaseEpisodeAdapter<BaseEpisodeHolder> {

        private TestAdapter(ArrayList<Episode> items) {
            super(items);
        }

        @NonNull
        @Override
        public BaseEpisodeHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void onBindViewHolder(@NonNull BaseEpisodeHolder holder, int position) {
        }
    }
}
