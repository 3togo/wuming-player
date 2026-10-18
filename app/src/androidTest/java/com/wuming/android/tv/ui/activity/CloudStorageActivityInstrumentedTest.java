package com.wuming.android.tv.ui.activity;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.wuming.android.tv.R;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CloudStorageActivityInstrumentedTest {

    @Test
    public void accountLoginHubSurvivesActivityRecreation() {
        try (ActivityScenario<CloudStorageActivity> scenario =
                     ActivityScenario.launch(CloudStorageActivity.class)) {
            assertScreen();
            scenario.recreate();
            assertScreen();
        }
    }

    private static void assertScreen() {
        onView(withId(R.id.back)).check(matches(isDisplayed()));
        onView(withId(R.id.cache)).check(matches(isDisplayed()));
        onView(withId(R.id.exportCredentials)).check(matches(isDisplayed()));
        onView(withId(R.id.importCredentials)).check(matches(isDisplayed()));
        onView(withId(R.id.connectors)).check(matches(isDisplayed()));
        onView(withId(R.id.connectors)).check(matches(not(isEnabled())));
        onView(withId(R.id.connectors)).check(matches(withText(R.string.webdav_connector_pending)));
        onView(withId(R.id.list)).check((view, error) -> {
            if (error != null) throw error;
            RecyclerView recycler = (RecyclerView) view;
            assertNotNull(recycler.getAdapter());
            assertEquals(6, recycler.getAdapter().getItemCount());
        });
    }
}
