package com.wuming.android.tv.ui.custom;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.widget.FrameLayout;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.wuming.android.tv.impl.ParseCallback;
import com.wuming.android.tv.utils.WebViewUtil;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class CustomWebViewOwnershipInstrumentedTest {
    @Test
    public void destroyingParserDestroysChildrenAndRejectsLateCreation() throws Exception {
        assumeTrue(WebViewUtil.support());
        AtomicReference<CustomWebView> owner = new AtomicReference<>();
        AtomicInteger callbacks = new AtomicInteger();
        Method add = CustomWebView.class.getDeclaredMethod("onParseAdd", Map.class, String.class);
        add.setAccessible(true);
        Field children = CustomWebView.class.getDeclaredField("children");
        children.setAccessible(true);
        Field destroyed = CustomWebView.class.getDeclaredField("destroyed");
        destroyed.setAccessible(true);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CustomWebView view = CustomWebView.create(ApplicationProvider.getApplicationContext());
            owner.set(view);
            FrameLayout parent = new FrameLayout(ApplicationProvider.getApplicationContext());
            parent.addView(view);
            view.start("", "", Map.of(), "about:blank", "", new ParseCallback() {
                @Override public void onParseSuccess(Map<String, String> headers, String url, String from) { callbacks.incrementAndGet(); }
                @Override public void onParseError() { callbacks.incrementAndGet(); }
            }, false);
            invoke(add, view);
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                CustomWebView view = owner.get();
                List<?> owned = (List<?>) children.get(view);
                assertEquals(1, owned.size());
                CustomWebView child = (CustomWebView) owned.get(0);
                view.destroy();
                view.destroy();
                assertTrue(destroyed.getBoolean(child));
                assertTrue(owned.isEmpty());
                assertNull(view.getParent());
                invoke(add, view);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertTrue(((List<?>) children.get(owner.get())).isEmpty());
        assertEquals(0, callbacks.get());
    }

    private static void invoke(Method add, CustomWebView view) {
        try { add.invoke(view, Map.of(), "about:blank"); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
}
