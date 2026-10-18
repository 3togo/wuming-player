package com.wuming.android.tv.api.projection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.wuming.android.tv.bean.Device;
import com.wuming.android.tv.dlna.DLNACastManager;
import com.wuming.android.tv.dlna.DlnaCastBackend;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class DlnaDiscoveryIntegrationInstrumentedTest {

    @Test
    public void realBackendDiscoversLanFakeRenderer() throws Exception {
        boolean enabled = Boolean.parseBoolean(InstrumentationRegistry.getArguments()
                .getString("run_dlna_discovery", "false"));
        Assume.assumeTrue("LAN DLNA discovery is opt-in", enabled);

        ProjectionManager manager = new ProjectionManager(new DlnaCastBackend());
        CountDownLatch discovered = new CountDownLatch(1);
        AtomicReference<Device> renderer = new AtomicReference<>();
        try {
            manager.discover(new ProjectionManager.DiscoveryCallback() {
                @Override
                public void onDeviceFound(Device device) {
                    if (!"FakeRenderer-Test".equals(device.getName())) return;
                    renderer.set(device);
                    discovered.countDown();
                }

                @Override
                public void onDeviceLost(Device device) {
                }
            });

            assertTrue("Fake renderer was not discovered over SSDP",
                    discovered.await(15, TimeUnit.SECONDS));
            assertEquals(2, renderer.get().getType());
            assertTrue(!renderer.get().getUuid().isEmpty());
        } finally {
            manager.stopDiscovery();
            DLNACastManager.get().release(
                    InstrumentationRegistry.getInstrumentation().getTargetContext());
        }
    }
}
