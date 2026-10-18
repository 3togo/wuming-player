package com.wuming.android.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Prevents connected tests from ever targeting the user's installed application data. */
@RunWith(AndroidJUnit4.class)
public class VerificationIsolationInstrumentedTest {

    @Test
    public void instrumentationTargetsTheIsolatedApplication() {
        String targetPackage = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getPackageName();
        String runnerPackage = InstrumentationRegistry.getInstrumentation()
                .getContext().getPackageName();

        assertEquals("com.wuming.android.tv.verification", targetPackage);
        assertNotEquals("com.wuming.android.tv", targetPackage);
        assertEquals("com.wuming.android.tv.verification.test", runnerPackage);
    }
}
