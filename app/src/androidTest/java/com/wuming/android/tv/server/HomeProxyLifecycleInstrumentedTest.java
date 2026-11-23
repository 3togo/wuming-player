package com.wuming.android.tv.server;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.wuming.android.tv.ui.activity.HomeActivity;
import java.net.HttpURLConnection;
import java.net.URL;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.assertEquals;

/** The proxy belongs to the process, even when the home screen is destroyed. */
@RunWith(AndroidJUnit4.class)
public class HomeProxyLifecycleInstrumentedTest {
    @Test public void destroyingAndReopeningHomeKeepsExistingProxyUrlsReachable() throws Exception {
        Server.get().start();
        String address = Server.get().getAddress(true);
        assertReachable(address);
        try (ActivityScenario<HomeActivity> home = ActivityScenario.launch(HomeActivity.class)) {
            assertReachable(address);
        }
        assertReachable(address);
        try (ActivityScenario<HomeActivity> home = ActivityScenario.launch(HomeActivity.class)) {
            assertReachable(address);
            assertEquals(address, Server.get().getAddress(true));
        }
        assertReachable(address);
    }

    private static void assertReachable(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address + "/").openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        try {
            assertEquals(200, connection.getResponseCode());
        } finally {
            connection.disconnect();
        }
    }
}
