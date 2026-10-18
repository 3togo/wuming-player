package com.wuming.android.tv.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;
import android.os.Build;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.wuming.android.tv.utils.NavigationGestureExclusion;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class NavigationGestureExclusionInstrumentedTest {

    @Test
    public void enabledNavigationExcludesItsLaidOutBoundsOnApi29AndLater() {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q);
        View navigation = new View(ApplicationProvider.getApplicationContext());

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NavigationGestureExclusion.apply(navigation, true);
            navigation.layout(0, 0, 240, 96);
        });

        assertEquals(1, navigation.getSystemGestureExclusionRects().size());
        assertEquals(new Rect(0, 0, 240, 96),
                navigation.getSystemGestureExclusionRects().get(0));
    }

    @Test
    public void disabledNavigationDoesNotCreateExclusionRects() {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q);
        View navigation = new View(ApplicationProvider.getApplicationContext());

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NavigationGestureExclusion.apply(navigation, false);
            navigation.layout(0, 0, 240, 96);
        });

        assertTrue(navigation.getSystemGestureExclusionRects().isEmpty());
    }
}
