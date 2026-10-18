package com.wuming.android.tv.ui;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;

import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.wuming.android.tv.R;
import com.wuming.android.tv.ui.activity.SettingActivity;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Repeatable gate for the depot "Use" -> config-add pre-fill flow.
 *
 * Seeds a single known VOD source via the debug-only OPEN_DEPOT intent extra (so the file lands
 * where the app actually reads it), opens the Depot dialog, taps the source's "Use" button, and
 * asserts the config-add dialog opens with its URL field pre-filled with the depot URL (not the
 * currently configured one). Runs on a TV via connectedLeanbackArm64_v8aVerificationAndroidTest.
 */
@RunWith(AndroidJUnit4.class)
public class DepotUseFlowInstrumentedTest {

    private static final String SEED_URL =
            "https://raw.githubusercontent.com/example/depot-seed/vod-seed.json";

    @Test
    public void useButtonOpensConfigDialogPreFilledWithDepotUrl() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent intent = new Intent(context, SettingActivity.class);
        intent.setAction(context.getPackageName() + ".action.OPEN_DEPOT");
        intent.putExtra(context.getPackageName() + ".extra.DEPOT_URL", SEED_URL);
        try (ActivityScenario<SettingActivity> scenario = TestActivityLauncher.launch(intent)) {
            // Exactly one "Use" button is displayed (the seeded VOD source); tap it. The "Test"
            // header row also has a GONE Use button, so constrain to the visible one.
            onView(allOf(withText(R.string.setting_depot_use), isDisplayed())).perform(click());
            // The config-add dialog must open with the depot URL pre-filled, not the current config.
            onView(withId(R.id.text)).check(matches(withText(containsString(SEED_URL))));
        }
    }
}
