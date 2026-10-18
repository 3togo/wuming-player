package com.wuming.android.tv.ui.activity;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static org.hamcrest.Matchers.not;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.wuming.android.tv.R;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class WebDavConnectorActivityInstrumentedTest {

    @Test
    public void connectorManagerFitsAndExposesPrimaryControls() {
        try (ActivityScenario<WebDavConnectorActivity> ignored =
                     ActivityScenario.launch(WebDavConnectorActivity.class)) {
            onView(withId(R.id.back)).check(matches(isDisplayed()));
            onView(withId(R.id.add)).check(matches(isDisplayed()));
            onView(withId(R.id.add)).check(matches(not(isEnabled())));
            onView(withId(R.id.list)).check(matches(isDisplayed()));
        }
    }
}
