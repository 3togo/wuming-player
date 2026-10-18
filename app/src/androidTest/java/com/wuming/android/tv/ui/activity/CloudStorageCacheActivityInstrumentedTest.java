package com.wuming.android.tv.ui.activity;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.wuming.android.tv.R;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CloudStorageCacheActivityInstrumentedTest {

    @Test
    public void cacheManagerSurvivesActivityRecreation() {
        try (ActivityScenario<CloudStorageCacheActivity> scenario =
                     ActivityScenario.launch(CloudStorageCacheActivity.class)) {
            assertScreen();
            scenario.recreate();
            assertScreen();
        }
    }

    private static void assertScreen() {
        onView(withId(R.id.back)).check(matches(isDisplayed()));
        onView(withId(R.id.list)).check(matches(isDisplayed()));
    }
}
