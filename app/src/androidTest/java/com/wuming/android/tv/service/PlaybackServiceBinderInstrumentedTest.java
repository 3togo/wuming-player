package com.wuming.android.tv.service;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.content.Intent;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ServiceTestRule;
import com.wuming.android.tv.BuildConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class PlaybackServiceBinderInstrumentedTest {
    @Rule public ServiceTestRule services = new ServiceTestRule();

    @Test public void binderHeldByClientCannotReturnDestroyedService() throws Exception {
        assumeTrue(BuildConfig.APPLICATION_ID.endsWith(".verification"));
        Intent intent = new Intent(ApplicationProvider.getApplicationContext(), PlaybackService.class)
                .setAction(PlaybackService.LOCAL_BIND_ACTION);
        PlaybackService.LocalBinder binder = (PlaybackService.LocalBinder) services.bindService(intent);
        assertNotNull(binder.getService());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> binder.getService().shutdown());
        services.unbindService();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (binder.getService() != null && System.nanoTime() < deadline) Thread.sleep(20);
        assertNull("Native or client binder references must not keep a destroyed service accessible", binder.getService());
    }
}
