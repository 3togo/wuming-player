package com.wuming.android.tv.utils;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.app.Activity;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.viewbinding.ViewBinding;
import androidx.media3.common.Player;

import com.wuming.android.tv.BuildConfig;
import com.wuming.android.tv.ui.base.BaseFragment;
import com.wuming.android.tv.ui.custom.CustomWallView;
import com.wuming.android.tv.ui.custom.CustomSeekView;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
public class ResourceLifecycleInstrumentedTest {
    @Test
    public void textScaleUsesPackagedResourceTagAndDoesNotScaleTwice() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            android.widget.TextView text = new android.widget.TextView(
                    ApplicationProvider.getApplicationContext());
            TextScale.apply(text);
            assertEquals(Boolean.TRUE, text.getTag(com.wuming.android.tv.R.id.tag_text_scaled));
            float firstSize = text.getTextSize();
            TextScale.apply(text);
            assertEquals(firstSize, text.getTextSize(), 0f);
            TextScale.resetAndApply(text);
            assertEquals(Boolean.TRUE, text.getTag(com.wuming.android.tv.R.id.tag_text_scaled));
        });
    }

    @Test
    public void fragmentViewWorkIsCancelledAcrossRecreation() {
        AtomicInteger staleCalls = new AtomicInteger();
        AtomicInteger currentCalls = new AtomicInteger();
        try (ActivityScenario<Activity> scenario = launch()) {
            scenario.onActivity(activity -> {
                FragmentActivity host = (FragmentActivity) activity;
                MemoryFragment fragment = new MemoryFragment();
                host.getSupportFragmentManager().beginTransaction()
                        .add(android.R.id.custom, fragment).commitNow();
                for (int i = 0; i < 10; i++) {
                    fragment.queue(staleCalls::incrementAndGet);
                    host.getSupportFragmentManager().beginTransaction().detach(fragment).commitNow();
                    fragment.queue(staleCalls::incrementAndGet);
                    host.getSupportFragmentManager().beginTransaction().attach(fragment).commitNow();
                }
                fragment.queue(currentCalls::incrementAndGet);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals(0, staleCalls.get());
            assertEquals(1, currentCalls.get());
        }
    }

    @Test
    public void seekListenerBelongsOnlyToAttachedViewAndCleanupClearsPlayer() {
        try (ActivityScenario<Activity> scenario = launch()) {
            scenario.onActivity(activity -> {
                Set<Player.Listener> listeners = new HashSet<>();
                Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                        new Class<?>[]{Player.class}, (proxy, method, args) -> {
                            switch (method.getName()) {
                                case "addListener": listeners.add((Player.Listener) args[0]); return null;
                                case "removeListener": listeners.remove(args[0]); return null;
                                case "getDuration": case "getCurrentPosition":
                                case "getBufferedPosition": return 0L;
                                case "isPlaying": return false;
                                default: throw new AssertionError("Unexpected player call: " + method.getName());
                            }
                        });
                FrameLayout root = activity.findViewById(android.R.id.custom);
                CustomSeekView seek = new CustomSeekView(activity);
                seek.setPlayer(player);
                assertTrue(listeners.isEmpty());
                for (int i = 0; i < 10; i++) {
                    root.addView(seek);
                    assertEquals(1, listeners.size());
                    root.removeView(seek);
                    assertTrue(listeners.isEmpty());
                }
                root.addView(seek);
                ViewCleanup.release(seek);
                assertTrue(listeners.isEmpty());
                root.removeView(seek);
                root.addView(seek);
                assertTrue(listeners.isEmpty());
            });
        }
    }

    @Test
    public void closingScopeCancelsQueuedWorkAndRejectsLateWorkerPosts() {
        AtomicInteger closedCalls = new AtomicInteger();
        AtomicInteger activeCalls = new AtomicInteger();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            UiTaskScope closed = new UiTaskScope();
            UiTaskScope active = new UiTaskScope();
            closed.post(closedCalls::incrementAndGet, 0);
            active.post(activeCalls::incrementAndGet, 0);
            closed.close();
            closed.close();
            closed.post(closedCalls::incrementAndGet, 0);
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertEquals(0, closedCalls.get());
        assertEquals(1, activeCalls.get());
    }

    @Test
    public void retainedFragmentAdapterReleasesEveryDestroyedView() {
        try (ActivityScenario<Activity> scenario = launch()) {
            scenario.onActivity(activity -> {
                FragmentActivity host = (FragmentActivity) activity;
                MemoryFragment fragment = new MemoryFragment();
                host.getSupportFragmentManager().beginTransaction()
                        .add(android.R.id.custom, fragment).commitNow();
                for (int i = 0; i < 10; i++) {
                    RecyclerView oldView = (RecyclerView) fragment.requireView();
                    assertTrue(fragment.adapter.hasObservers());
                    host.getSupportFragmentManager().beginTransaction().detach(fragment).commitNow();
                    assertNull(oldView.getAdapter());
                    assertFalse(fragment.adapter.hasObservers());
                    host.getSupportFragmentManager().beginTransaction().attach(fragment).commitNow();
                    assertNotSame(oldView, fragment.requireView());
                }
                host.getSupportFragmentManager().beginTransaction().remove(fragment).commitNow();
                assertFalse(fragment.adapter.hasObservers());
            });
        }
    }

    @Test
    public void wallpaperReattachmentDoesNotAccumulateInflatedChildren() {
        try (ActivityScenario<Activity> scenario = launch()) {
            scenario.onActivity(activity -> {
                FrameLayout root = activity.findViewById(android.R.id.custom);
                CustomWallView wall = new CustomWallView(activity, null);
                for (int i = 0; i < 10; i++) {
                    root.addView(wall);
                    assertTrue(wall.getChildCount() > 0);
                    root.removeView(wall);
                    assertEquals(0, wall.getChildCount());
                }
            });
        }
    }

    private static ActivityScenario<Activity> launch() {
        assumeTrue(BuildConfig.APPLICATION_ID.endsWith(".verification"));
        Intent intent = new Intent().setClassName(ApplicationProvider.getApplicationContext(),
                "com.wuming.android.tv.ui.activity.MemoryTestActivity");
        return ActivityScenario.launch(intent);
    }

    public static class MemoryFragment extends BaseFragment {
        void queue(Runnable task) { postUi(task); }
        final RecyclerView.Adapter<RecyclerView.ViewHolder> adapter = new RecyclerView.Adapter<>() {
            @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
                return new RecyclerView.ViewHolder(new View(parent.getContext())) {};
            }
            @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {}
            @Override public int getItemCount() { return 0; }
        };
        private RecyclerView recycler;
        @Override protected ViewBinding getBinding(@NonNull LayoutInflater inflater, ViewGroup container) {
            recycler = new RecyclerView(inflater.getContext());
            return () -> recycler;
        }
        @Override protected void initView() { recycler.setAdapter(adapter); }
        @Override protected void releaseBinding() { recycler = null; }
    }
}
