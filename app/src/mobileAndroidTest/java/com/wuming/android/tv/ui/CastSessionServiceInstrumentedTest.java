package com.wuming.android.tv.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.os.Build;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.wuming.android.tv.service.CastSessionService;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CastSessionServiceInstrumentedTest {

    @Test
    public void mobileCastControllerIsPrivateConnectedDeviceService() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        ServiceInfo info = context.getPackageManager().getServiceInfo(
                new ComponentName(context, CastSessionService.class), 0);

        assertFalse(info.exported);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                    info.getForegroundServiceType()
                            & ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        }
    }
}
