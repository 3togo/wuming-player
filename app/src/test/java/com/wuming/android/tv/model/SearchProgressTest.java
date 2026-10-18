package com.wuming.android.tv.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import org.junit.Rule;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SearchProgressTest {

    @Rule
    public InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    @Test
    public void duplicateCompletionIsCountedOnlyOnce() {
        SearchProgress progress = new SearchProgress();
        progress.startKeys(List.of("one", "two"));

        progress.onSiteCompleted("one");
        progress.onSiteCompleted("one");
        progress.onSiteFailed("unknown");

        assertEquals(1, progress.getCompletedCount());
        progress.onSiteFailed("two");
        assertTrue(progress.isComplete());
        assertEquals("2/2 completed", progress.getProgress().getValue().getProgressText());
    }

    @Test
    public void concurrentCallbacksCannotCompleteTheSameSiteTwice() throws Exception {
        SearchProgress progress = new SearchProgress();
        progress.startKeys(List.of("site"));
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch latch = new CountDownLatch(32);

        for (int i = 0; i < 32; i++) {
            executor.execute(() -> {
                progress.onSiteCompleted("site");
                latch.countDown();
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        executor.shutdownNow();
        assertEquals(1, progress.getCompletedCount());
        assertTrue(progress.isComplete());
    }

    @Test
    public void resetClearsCountsAndPublishedText() {
        SearchProgress progress = new SearchProgress();
        progress.startKeys(List.of("site"));
        progress.onSiteCompleted("site");

        progress.reset();

        assertEquals(0, progress.getCompletedCount());
        assertEquals(0, progress.getTotalCount());
        assertEquals("", progress.getProgress().getValue().getProgressText());
    }
}
