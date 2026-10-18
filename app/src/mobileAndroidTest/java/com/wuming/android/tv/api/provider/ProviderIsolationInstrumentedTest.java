package com.wuming.android.tv.api.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.os.Process;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ProviderIsolationInstrumentedTest {

    @Test
    public void providerProcessCrashDoesNotKillUiAndReconnects() throws Exception {
        ProviderClient client = ProviderClient.get();
        int uiPid = Process.myPid();
        assertTrue("Provider process did not start", client.ping());

        try {
            client.crashForTest();
        } catch (ProviderException expected) {
            // The response pipe closes when the provider process is killed.
        }

        assertEquals("Provider crash killed the UI process", uiPid, Process.myPid());
        long deadline = System.currentTimeMillis() + 10_000L;
        boolean reconnected = false;
        while (System.currentTimeMillis() < deadline && !(reconnected = client.ping())) {
            Thread.sleep(250L);
        }
        assertTrue("Provider process did not reconnect", reconnected);
        assertEquals("UI process changed during provider recovery", uiPid, Process.myPid());
    }
}
