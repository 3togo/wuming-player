package com.wuming.android.tv.ui.activity;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.hamcrest.Matchers.not;

import android.content.Intent;
import android.os.SystemClock;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.lifecycle.Lifecycle;

import com.wuming.android.tv.R;
import com.wuming.android.tv.cloud.CloudStorageProvider;
import com.wuming.android.tv.cloud.opendrive.OpenDriveEngine;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CloudStorageWebActivityInstrumentedTest {

    @Test
    public void quarkCompatibilityProfileIsLimitedToLoggedOutLoginPage() {
        assertTrue(CloudStorageWebActivity.usesQuarkLoginProfile(
                CloudStorageProvider.QUARK, false));
        assertFalse(CloudStorageWebActivity.usesQuarkLoginProfile(
                CloudStorageProvider.QUARK, true));
        assertFalse(CloudStorageWebActivity.usesQuarkLoginProfile(
                CloudStorageProvider.UC, false));
        assertTrue(CloudStorageWebActivity.usesQuarkManagementProfile(
                CloudStorageProvider.QUARK, true));
        assertFalse(CloudStorageWebActivity.usesQuarkManagementProfile(
                CloudStorageProvider.QUARK, false));
    }

    @Test
    public void managementWebViewUsesMobileBrowserProfile() {
        try (ActivityScenario<CloudStorageWebActivity> scenario = launch("baidu")) {
            scenario.onActivity(activity -> {
                WebView web = activity.findViewById(R.id.web);
                WebSettings settings = web.getSettings();
                String userAgent = settings.getUserAgentString();
                assertTrue(userAgent.contains("Android"));
                assertTrue(userAgent.contains("Mobile"));
                assertFalse(userAgent.contains("X11; Linux x86_64"));
                assertFalse(settings.getUseWideViewPort());
                assertFalse(settings.getLoadWithOverviewMode());
            });
        }
    }

    @Test
    public void quarkLoginUsesCompatibleProfileUntilAuthenticated() {
        boolean authenticated = new OpenDriveEngine().provider("quark").authenticated();
        try (ActivityScenario<CloudStorageWebActivity> scenario = launch("quark")) {
            scenario.onActivity(activity -> {
                WebSettings settings = ((WebView) activity.findViewById(R.id.web)).getSettings();
                if (authenticated) {
                    assertTrue(settings.getUserAgentString().contains("Mobile"));
                    assertFalse(settings.getUseWideViewPort());
                    assertFalse(settings.getLoadWithOverviewMode());
                } else {
                    assertFalse(settings.getUserAgentString().contains("Mobile"));
                    assertTrue(settings.getUseWideViewPort());
                    assertTrue(settings.getLoadWithOverviewMode());
                }
            });
        }
    }

    @Test
    public void builtInProviderShowsAutoQrHandoff() {
        try (ActivityScenario<CloudStorageWebActivity> ignored = launch("quark")) {
            onView(withId(R.id.auto)).check(matches(isDisplayed()));
            onView(withId(R.id.reset)).check(matches(isDisplayed()));
            onView(withId(R.id.external)).check(matches(isDisplayed()));
        }
    }

    @Test
    public void unsupportedProviderHidesAutoQrHandoff() {
        try (ActivityScenario<CloudStorageWebActivity> ignored = launch("115")) {
            onView(withId(R.id.auto)).check(matches(not(isDisplayed())));
            onView(withId(R.id.reset)).check(matches(isDisplayed()));
            onView(withId(R.id.external)).check(matches(isDisplayed()));
        }
    }

    @Test
    public void managementModeCannotResetCredentialsAndBackAlwaysReturnsToApp() {
        Intent intent = new Intent(ApplicationProvider.getApplicationContext(),
                CloudStorageWebActivity.class);
        intent.putExtra("provider", "ali");
        intent.putExtra("management_only", true);
        try (ActivityScenario<CloudStorageWebActivity> scenario =
                     ActivityScenario.launch(intent)) {
            onView(withId(R.id.title)).check(matches(isDisplayed()));
            onView(withId(R.id.reset)).check(matches(not(isDisplayed())));
            onView(withId(R.id.auto)).check(matches(not(isDisplayed())));
            onView(withId(R.id.back)).perform(click());
            for (int attempt = 0; attempt < 20
                    && scenario.getState() != Lifecycle.State.DESTROYED; attempt++) {
                SystemClock.sleep(100);
            }
            assertEquals(Lifecycle.State.DESTROYED, scenario.getState());
        }
    }

    private static ActivityScenario<CloudStorageWebActivity> launch(String provider) {
        Intent intent = new Intent(ApplicationProvider.getApplicationContext(),
                CloudStorageWebActivity.class);
        intent.putExtra("provider", provider);
        return ActivityScenario.launch(intent);
    }
}
