package com.wuming.tv.guardhost.webview;

import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Hardware integration test for {@link AdbWebViewEngine}. It only runs when a device serial is supplied
 * via {@code -Dtvbox.guarded.hardware.test.serial=<serial>} AND an {@code adb forward tcp:8765 tcp:8765}
 * tunnel is already established to a running {@code guarded-webview-helper} app. It proves the desktop
 * engine connects to the real device WebView and relays onPageFinished back to the provider bridge.
 */
public class AdbWebViewEngineHardwareTest {

    @Test
    public void loadUrlRelaysDevicePageFinishedToBridge() throws Exception {
        Assume.assumeNotNull(
            "Set -Dtvbox.guarded.hardware.test.serial and forward tcp:8765 to run this hardware test",
            System.getProperty("tvbox.guarded.hardware.test.serial"));

        WebViewEngine engine = new AdbWebViewEngine();
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<String> finishedUrl = new AtomicReference<>();
        WebViewClientBridge bridge = new WebViewClientBridge() {
            @Override
            public void onPageStarted(String url) {
            }

            @Override
            public void onPageFinished(String url) {
                finishedUrl.set(url);
                finished.countDown();
            }

            @Override
            public void onLoadResource(String url) {
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebResourceRequest request) {
                return false;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
                return null;
            }
        };

        engine.loadUrl("https://www.example.com/", Collections.emptyMap(), bridge);
        assertTrue("device WebView onPageFinished not received within timeout",
            finished.await(20, TimeUnit.SECONDS));
        assertEquals("https://www.example.com/", finishedUrl.get());
        engine.destroy();
    }
}
