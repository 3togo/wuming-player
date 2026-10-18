package com.wuming.android.tv.api.projection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.wuming.android.tv.bean.CastVideo;
import com.wuming.android.tv.bean.Device;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(AndroidJUnit4.class)
public class ProjectionManagerBackendInstrumentedTest {

    @Test
    public void injectedBackendForwardsDiscoveryAndSuppressesLateEvents() throws Exception {
        FakeBackend backend = new FakeBackend();
        ProjectionManager manager = new ProjectionManager(backend);
        AtomicInteger found = new AtomicInteger();
        AtomicInteger lost = new AtomicInteger();
        CountDownLatch callbacks = new CountDownLatch(2);
        Device device = new Device();
        device.setUuid("fake-renderer");
        device.setName("Fake renderer");
        device.setType(2);

        manager.discover(new ProjectionManager.DiscoveryCallback() {
            @Override
            public void onDeviceFound(Device discovered) {
                assertEquals(device, discovered);
                found.incrementAndGet();
                callbacks.countDown();
            }

            @Override
            public void onDeviceLost(Device discovered) {
                assertEquals(device, discovered);
                lost.incrementAndGet();
                callbacks.countDown();
            }
        });

        assertNotNull(backend.context);
        assertNotNull(backend.listener);
        backend.listener.onDeviceAdded(device);
        backend.listener.onDeviceRemoved(device);
        assertTrue(callbacks.await(20, TimeUnit.SECONDS));
        assertEquals(1, found.get());
        assertEquals(1, lost.get());

        manager.stopDiscovery();
        assertTrue(backend.stopped);
        backend.listener.onDeviceAdded(device);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertEquals(1, found.get());
    }

    private static final class FakeBackend implements CastBackend {
        private Context context;
        private DiscoveryListener listener;
        private boolean stopped;

        @Override
        public void startDiscovery(Context context, DiscoveryListener listener) {
            this.context = context;
            this.listener = listener;
        }

        @Override
        public void stopDiscovery() {
            stopped = true;
        }

        @Override
        public Session createSession(CastVideo video, Runnable onPlaying, Runnable onEnded,
                                     Runnable onFailed) {
            throw new AssertionError("Discovery must not create a cast session");
        }
    }
}
