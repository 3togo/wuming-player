package com.wuming.android.tv.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.wuming.android.tv.ui.activity.HomeActivity;
import com.wuming.android.tv.ui.activity.VideoActivity;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CriticalManifestInstrumentedTest {

    @Test
    public void launcherResolvesToHomeActivity() {
        Context context = ApplicationProvider.getApplicationContext();
        Intent launcher = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());

        assertNotNull(launcher);
        assertEquals(HomeActivity.class.getName(), launcher.getComponent().getClassName());
    }

    @Test
    public void criticalActivitiesAreDeclaredAndResizable() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        PackageManager packageManager = context.getPackageManager();

        assertDeclared(packageManager, context, HomeActivity.class);
        assertDeclared(packageManager, context, VideoActivity.class);
    }

    private static void assertDeclared(PackageManager manager, Context context, Class<?> activity) throws Exception {
        ActivityInfo info = manager.getActivityInfo(new ComponentName(context, activity), 0);
        assertNotNull(info);
        assertTrue(info.enabled);
    }
}
