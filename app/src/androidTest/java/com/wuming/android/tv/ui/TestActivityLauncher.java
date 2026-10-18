package com.wuming.android.tv.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;

import com.wuming.android.tv.ui.activity.HomeActivity;

import java.io.IOException;
import java.io.InputStream;

final class TestActivityLauncher {

    private static boolean foregrounded;

    private TestActivityLauncher() {
    }

    static <A extends android.app.Activity> ActivityScenario<A> launch(Class<A> activityClass) {
        Context context = ApplicationProvider.getApplicationContext();
        return launch(new Intent(context, activityClass));
    }

    static <A extends android.app.Activity> ActivityScenario<A> launch(Intent intent) {
        bringAppToForegroundOnce();
        android.app.UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        // Shell identity adoption is unavailable on Android 9 and older TVs.
        boolean canAdoptIdentity = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
        if (canAdoptIdentity) {
            automation.adoptShellPermissionIdentity("android.permission.START_ACTIVITIES_FROM_BACKGROUND");
        }
        try {
            // The for-result path avoids clearing the task which owns the instrumentation
            // runner. Shell launch identity is needed on OEM builds which block the first
            // test activity while the app is backgrounded.
            return ActivityScenario.launchActivityForResult(intent);
        } finally {
            if (canAdoptIdentity) automation.dropShellPermissionIdentity();
        }
    }

    private static synchronized void bringAppToForegroundOnce() {
        if (foregrounded) return;
        android.app.UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        String targetPackage = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getPackageName();
        ParcelFileDescriptor descriptor = automation.executeShellCommand(
                "am start -W -n " + targetPackage + "/" + HomeActivity.class.getName());
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            byte[] buffer = new byte[256];
            while (input.read(buffer) != -1) {
                // Drain command output so am has completed before ActivityScenario starts.
            }
        } catch (IOException e) {
            throw new AssertionError("Unable to foreground app before activity test", e);
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        foregrounded = true;
    }
}
