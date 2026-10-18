package com.wuming.android.tv.recommendation;

import static org.junit.Assert.*;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import com.wuming.android.tv.bean.Vod;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.Rule;
import org.junit.Test;

public class RecommendationRequestTest {
    @Rule public InstantTaskExecutorRule rule = new InstantTaskExecutorRule();

    @Test public void newerResultWinsEvenWhenOlderRankingFinishesLast() {
        Backend backend = new Backend();
        RecommendationRequest request = new RecommendationRequest(backend);
        Owner owner = new Owner();
        List<String> rendered = new ArrayList<>();
        request.rank(owner, List.of(), "site", () -> "site", items -> rendered.add("old"));
        request.rank(owner, List.of(), "site", () -> "site", items -> rendered.add("new"));
        backend.complete(1);
        backend.complete(0);
        backend.complete(1); // Duplicate completion must not append twice.
        assertEquals(List.of("new"), rendered);
        assertEquals(0, owner.lifecycle.getObserverCount());
    }

    @Test public void refreshRejectsOldRowsBeforeReplacementResultArrives() {
        Backend backend = new Backend();
        RecommendationRequest request = new RecommendationRequest(backend);
        Owner owner = new Owner();
        List<String> effects = new ArrayList<>();
        request.rank(owner, List.of(), "site", () -> "site", items -> effects.add("rows"));
        request.invalidate();
        backend.complete(0);
        assertTrue(effects.isEmpty());
        assertEquals(0, owner.lifecycle.getObserverCount());
    }

    @Test public void sourceChangeRejectsBothRenderingAndCategoryNavigation() {
        Backend backend = new Backend();
        RecommendationRequest request = new RecommendationRequest(backend);
        AtomicReference<String> source = new AtomicReference<>("A");
        List<String> effects = new ArrayList<>();
        request.rank(new Owner(), List.of(), source.get(), source::get, items -> {
            effects.add("rows");
            effects.add("open categories");
        });
        source.set("B");
        backend.complete(0);
        assertTrue(effects.isEmpty());
    }

    @Test public void destroyedViewCannotDeliverIntoRecreatedView() {
        Backend backend = new Backend();
        RecommendationRequest request = new RecommendationRequest(backend);
        Owner oldView = new Owner();
        Owner newView = new Owner();
        List<String> effects = new ArrayList<>();
        request.rank(oldView, List.of(), "site", () -> "site", items -> effects.add("old view"));
        oldView.destroy();
        backend.complete(0);
        request.rank(newView, List.of(), "site", () -> "site", items -> effects.add("new view"));
        backend.complete(0);
        backend.complete(1);
        assertEquals(List.of("new view"), effects);
        assertEquals(0, oldView.lifecycle.getObserverCount());
        assertEquals(0, newView.lifecycle.getObserverCount());
    }

    @Test public void closingOneScreenLeavesOtherScreensSharedWorkDeliverable() {
        Backend backend = new Backend();
        Owner first = new Owner();
        Owner second = new Owner();
        List<String> effects = new ArrayList<>();
        new RecommendationRequest(backend).rank(first, List.of(), "site", () -> "site",
                items -> effects.add("first"));
        new RecommendationRequest(backend).rank(second, List.of(), "site", () -> "site",
                items -> effects.add("second"));
        first.destroy();
        backend.complete(0);
        backend.complete(1);
        assertEquals(List.of("second"), effects);
    }

    @Test public void destroyedOwnerDoesNotStartRanking() {
        Backend backend = new Backend();
        Owner owner = new Owner();
        owner.destroy();
        new RecommendationRequest(backend).rank(owner, List.of(), "site", () -> "site",
                items -> fail("Destroyed owner received a result"));
        assertTrue(backend.callbacks.isEmpty());
        assertEquals(0, owner.lifecycle.getObserverCount());
    }

    private static final class Backend implements RecommendationRequest.Ranker {
        final List<Consumer<List<Vod>>> callbacks = new ArrayList<>();
        @Override public void rank(List<Vod> candidates, String site, Consumer<List<Vod>> callback) {
            callbacks.add(callback);
        }
        void complete(int index) { callbacks.get(index).accept(List.of()); }
    }

    private static final class Owner implements LifecycleOwner {
        final LifecycleRegistry lifecycle = new LifecycleRegistry(this);
        Owner() { lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE); }
        @Override public Lifecycle getLifecycle() { return lifecycle; }
        void destroy() { lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY); }
    }
}
