package com.wuming.android.tv.ui.adapter;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.wuming.android.tv.bean.Device;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DeviceAdapterInstrumentedTest {
    @Test public void rapidDiscoveryDoesNotLosePendingRenderers() throws Exception {
        CountDownLatch committed = new CountDownLatch(1);
        DeviceAdapter[] holder = new DeviceAdapter[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            DeviceAdapter adapter = new DeviceAdapter(new DeviceAdapter.OnClickListener() {
                public void onItemClick(Device device) {}
                public boolean onLongClick(Device device) { return false; }
            });
            holder[0] = adapter;
            for (int i = 0; i < 12; i++) {
                Device device = new Device();
                device.setUuid("smoke-renderer-" + i);
                device.setName("Renderer " + i);
                device.setType(2);
                adapter.sort(device);
            }
            assertEquals(12, adapter.getItems().size());
            adapter.setItems(adapter.getItems(), committed::countDown);
        });
        assertTrue(committed.await(10, TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> assertEquals(12, holder[0].getItemCount()));
    }
}
