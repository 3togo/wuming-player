package com.wuming.android.tv.cloud.opendrive;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import okhttp3.Response;

@RunWith(AndroidJUnit4.class)
public class QuarkProbeDeadlineInstrumentedTest {
    @Test public void stalledTlsHandshakeHasTotalDeadline() throws Exception {
        verifyDeadline(true);
    }

    @Test public void tricklingPrefixBodyHasSameTotalDeadline() throws Exception {
        verifyDeadline(false);
    }

    private void verifyDeadline(boolean tls) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch accepted = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(6000);
            Future<?> peer = executor.submit(() -> {
                try (Socket socket = server.accept()) {
                    accepted.countDown();
                    socket.setSoTimeout(6000);
                    if (!tls) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(
                                socket.getInputStream(), StandardCharsets.US_ASCII));
                        String line;
                        while ((line = reader.readLine()) != null && !line.isEmpty()) { }
                        socket.getOutputStream().write(("HTTP/1.1 206 Partial Content\r\n"
                                + "Content-Length: 16\r\nContent-Range: bytes 0-15/100\r\n"
                                + "Connection: close\r\n\r\nx").getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().flush();
                    }
                    release.await(6, TimeUnit.SECONDS);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            long start = SystemClock.elapsedRealtime();
            try (Response response = new QuarkNetwork.NetworkTransport().openProbe(
                    (tls ? "https" : "http") + "://127.0.0.1:" + server.getLocalPort() + "/media",
                    "bytes=0-15", "")) {
                QuarkOpenDriveProvider.readPrefix(response.body().byteStream(), 16);
                fail("Stalled prefix probe should time out");
            } catch (java.io.IOException expected) {
                long elapsed = SystemClock.elapsedRealtime() - start;
                assertTrue("Probe did not reach the local peer", accepted.getCount() == 0);
                assertTrue("Probe failed before exercising its deadline: " + elapsed, elapsed >= 2000);
                assertTrue("Probe exceeded total deadline: " + elapsed, elapsed < 5500);
            } finally { release.countDown(); }
            peer.get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
