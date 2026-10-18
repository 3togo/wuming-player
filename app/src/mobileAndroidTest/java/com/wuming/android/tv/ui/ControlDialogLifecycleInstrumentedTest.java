package com.wuming.android.tv.ui;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.app.Activity;
import android.content.Intent;
import androidx.fragment.app.FragmentActivity;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.wuming.android.tv.BuildConfig;
import com.wuming.android.tv.ui.dialog.ControlDialog;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;

@RunWith(AndroidJUnit4.class)
public class ControlDialogLifecycleInstrumentedTest {
    @Test public void restoredControlWithoutPlayerDismissesAndClearsViewReferences() {
        assumeTrue(BuildConfig.APPLICATION_ID.endsWith(".verification"));
        Intent intent = new Intent().setClassName(ApplicationProvider.getApplicationContext(),
                "com.wuming.android.tv.ui.activity.MemoryTestActivity");
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(intent)) {
            ControlDialog dialog = new ControlDialog();
            scenario.onActivity(activity -> dialog.showNow(
                    ((FragmentActivity) activity).getSupportFragmentManager(), "restored-control"));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                assertNull(((FragmentActivity) activity).getSupportFragmentManager()
                        .findFragmentByTag("restored-control"));
                for (String name : new String[]{"binding", "parentBinding", "playerManager", "ownerActivity", "listener", "history"}) {
                    assertNull(field(dialog, name));
                }
                assertTrue(((java.util.List<?>) field(dialog, "scales")).isEmpty());
            });
        }
    }

    @Test public void delayedHandoffRunsAfterDialogCleanupButStopsForBackgroundOrDestroyedHost() throws Exception {
        assumeTrue(BuildConfig.APPLICATION_ID.endsWith(".verification"));
        Intent intent = new Intent().setClassName(ApplicationProvider.getApplicationContext(),
                "com.wuming.android.tv.ui.activity.MemoryTestActivity");
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(intent)) {
            java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
            scenario.onActivity(activity -> queueHandoff((FragmentActivity) activity, calls));
            Thread.sleep(300);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals(1, calls.get());
            scenario.onActivity(activity -> {
                queueHandoff((FragmentActivity) activity, calls);
                InstrumentationRegistry.getInstrumentation().callActivityOnSaveInstanceState(activity, new android.os.Bundle());
            });
            Thread.sleep(300);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals(1, calls.get());
            scenario.recreate();
            scenario.onActivity(activity -> queueHandoff((FragmentActivity) activity, calls));
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
            Thread.sleep(300);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals(1, calls.get());
        }
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(intent)) {
            java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
            scenario.onActivity(activity -> {
                queueHandoff((FragmentActivity) activity, calls);
                activity.finish();
            });
            Thread.sleep(300);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals(0, calls.get());
        }
    }

    private static void queueHandoff(FragmentActivity activity, java.util.concurrent.atomic.AtomicInteger calls) {
        try {
            ControlDialog dialog = new ControlDialog();
            Field owner = ControlDialog.class.getDeclaredField("ownerActivity");
            owner.setAccessible(true);
            owner.set(dialog, activity);
            java.lang.reflect.Method handoff = ControlDialog.class.getDeclaredMethod("handoff", kotlin.jvm.functions.Function1.class);
            handoff.setAccessible(true);
            kotlin.jvm.functions.Function1<FragmentActivity, kotlin.Unit> action = host -> {
                assertSame(activity, host);
                calls.incrementAndGet();
                return kotlin.Unit.INSTANCE;
            };
            handoff.invoke(dialog, action);
            java.lang.reflect.Method cleanup = ControlDialog.class.getDeclaredMethod("releaseBinding");
            cleanup.setAccessible(true);
            cleanup.invoke(dialog);
            assertNull(field(dialog, "ownerActivity"));
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static Object field(Object owner, String name) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
}
