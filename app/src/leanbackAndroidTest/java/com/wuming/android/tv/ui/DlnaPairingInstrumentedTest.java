package com.wuming.android.tv.ui;

import android.content.*;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.IBinder;
import android.view.KeyEvent;
import android.widget.ImageView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.wuming.android.tv.R;
import com.wuming.android.tv.server.DlnaPairing;
import com.wuming.android.tv.server.Server;
import com.wuming.android.tv.service.DLNARendererService;
import com.wuming.android.tv.ui.activity.DlnaPairingActivity;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import org.fourthline.cling.UpnpService;
import org.fourthline.cling.android.AndroidUpnpServiceImpl;
import org.fourthline.cling.model.NetworkAddress;
import org.fourthline.cling.model.meta.LocalDevice;
import org.fourthline.cling.model.meta.LocalService;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DlnaPairingInstrumentedTest {
    private final OkHttpClient client = new OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build();

    @Test public void screenQrHttpAndRealDlnaTransport() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        instrumentation.setInTouchMode(false);
        AtomicReference<DLNARendererService> renderer = new AtomicReference<>();
        CountDownLatch connected = new CountDownLatch(1);
        ServiceConnection connection = new ServiceConnection() {
            public void onServiceConnected(ComponentName name, IBinder binder) {
                renderer.set(((DLNARendererService.LocalBinder) binder).getService()); connected.countDown();
            }
            public void onServiceDisconnected(ComponentName name) {}
        };
        try (ActivityScenario<DlnaPairingActivity> scenario = TestActivityLauncher.launch(DlnaPairingActivity.class)) {
            DlnaPairing.SESSION.revoke();
            key(scenario, KeyEvent.KEYCODE_DPAD_CENTER);
            instrumentation.waitForIdleSync();
            AtomicReference<Bitmap> qr = new AtomicReference<>();
            scenario.onActivity(activity ->
                    assertTrue(activity.findViewById(R.id.refresh).hasFocus()));
            // The QR is generated off the main thread and posted back, so idle sync alone does
            // not guarantee the code is on screen. Sampling the drawable once captured an
            // unrendered image and zxing threw NotFoundException; poll until it decodes.
            String decoded = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            do {
                scenario.onActivity(activity -> qr.set(((BitmapDrawable) ((ImageView) activity
                        .findViewById(R.id.code)).getDrawable()).getBitmap()));
                Bitmap bitmap = qr.get();
                int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
                bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
                try {
                    decoded = new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(
                            new RGBLuminanceSource(bitmap.getWidth(), bitmap.getHeight(), pixels)))).getText();
                } catch (com.google.zxing.NotFoundException notRenderedYet) {
                    decoded = null;
                    instrumentation.waitForIdleSync();
                }
            } while (decoded == null && System.nanoTime() < deadline);
            assertNotNull("Pairing QR was never rendered", decoded);
            Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
            assertNotNull(screenshot);
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(context.getExternalFilesDir(null), "dlna-pairing-screen.png"))) {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            assertTrue(decoded.contains("/dlna-pair.html#code="));
            String code = decoded.substring(decoded.indexOf("#code=")+6);
            String base = Server.get().getAddress();
            assertEquals(200, request(base+"/dlna-pair.html", null, null));
            assertEquals(405, request(base+"/dlna-pair", null, null));
            assertEquals(403, request(base+"/dlna-pair", "", "wrong"));
            assertTrue(context.bindService(new Intent(context,DLNARendererService.class), connection, Context.BIND_AUTO_CREATE));
            try {
                assertTrue(connected.await(15,TimeUnit.SECONDS));
                Field field = AndroidUpnpServiceImpl.class.getDeclaredField("upnpService"); field.setAccessible(true);
                UpnpService upnp = (UpnpService)field.get(renderer.get());
                NetworkAddress address = upnp.getRouter().getActiveStreamServers(null).get(0);
                LocalDevice device = upnp.getRegistry().getLocalDevices().iterator().next();
                LocalService transport = null;
                for (LocalService service : device.getServices()) if (service.getServiceType().getType().equals("AVTransport")) transport=service;
                assertNotNull(transport);
                String origin = "http://"+address.getAddress().getHostAddress()+":"+address.getPort();
                String control = origin+upnp.getConfiguration().getNamespace().getControlPath(transport);
                assertEquals(200,request(origin+upnp.getConfiguration().getNamespace().getDescriptorPath(device),null,null));
                assertEquals(403,soap(control));
                assertEquals(200,request(base+"/dlna-pair","",code));
                assertEquals(403,request(base+"/dlna-pair","",code));
                assertEquals(200,soap(control));
                android.util.Log.i("DlnaPairingTest", "PASS: QR decoded, page HTTP 200, unpaired SOAP 403, claim 200, replay 403, paired SOAP 200");
                key(scenario, KeyEvent.KEYCODE_DPAD_DOWN);
                scenario.onActivity(activity -> assertTrue(activity.findViewById(R.id.revoke).hasFocus()));
                key(scenario, KeyEvent.KEYCODE_DPAD_CENTER);
                instrumentation.waitForIdleSync();
                assertEquals(403,soap(control));
                android.util.Log.i("DlnaPairingTest", "PASS: D-pad disconnect revoked SOAP access (403)");
                key(scenario, KeyEvent.KEYCODE_DPAD_UP);
                scenario.onActivity(activity -> assertTrue(activity.findViewById(R.id.refresh).hasFocus()));
                key(scenario, KeyEvent.KEYCODE_BACK);
                instrumentation.waitForIdleSync();
                assertEquals("",DlnaPairing.SESSION.code());
            } finally { context.unbindService(connection); }
        } finally { DlnaPairing.SESSION.revoke(); }
    }
    private void key(ActivityScenario<DlnaPairingActivity> scenario, int code) throws Exception {
        java.util.concurrent.atomic.AtomicBoolean focused = new java.util.concurrent.atomic.AtomicBoolean();
        for (int attempt = 0; attempt < 50; attempt++) {
            scenario.onActivity(activity -> focused.set(activity.hasWindowFocus()));
            if (focused.get()) break;
            Thread.sleep(100);
        }
        assertTrue("Pairing activity must own the input window", focused.get());
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        long now = android.os.SystemClock.uptimeMillis();
        for (int action : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
            assertTrue(instrumentation.getUiAutomation().injectInputEvent(new KeyEvent(now,
                    android.os.SystemClock.uptimeMillis(), action, code, 0, 0,
                    android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                    android.view.InputDevice.SOURCE_DPAD), true));
        }
        instrumentation.waitForIdleSync();
    }
    private int request(String url,String body,String code) throws Exception {
        Request.Builder builder = new Request.Builder().url(url);
        if(body!=null) builder.post(RequestBody.create(body,MediaType.get("text/plain")));
        if(code!=null) builder.header("X-Pairing-Code",code);
        try(Response response=client.newCall(builder.build()).execute()){return response.code();}
    }
    private int soap(String url) throws Exception {
        String body="<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:GetTransportInfo xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"><InstanceID>0</InstanceID></u:GetTransportInfo></s:Body></s:Envelope>";
        Request request=new Request.Builder().url(url).header("SOAPAction","\"urn:schemas-upnp-org:service:AVTransport:1#GetTransportInfo\"")
            .post(RequestBody.create(body,MediaType.get("text/xml; charset=utf-8"))).build();
        try(Response response=client.newCall(request).execute()){return response.code();}
    }
}
