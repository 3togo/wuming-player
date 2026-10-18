package com.wuming.android.tv.server;

import com.wuming.android.tv.ProductCore;
import org.fourthline.cling.protocol.ProtocolFactory;
import org.junit.After;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class DlnaPairingTransportTest {
    @After public void clear() { DlnaPairing.SESSION.revoke(); }

    @Test public void unpairedSoapCannotReachTheUpnpDispatcherEvenWithForgedHeaders() throws Exception {
        AtomicInteger dispatches = new AtomicInteger();
        String response = runRequest("POST", dispatches);
        if (ProductCore.getDeviceType() == 0) {
            assertTrue(response, response.startsWith("HTTP/1.1 403"));
            assertEquals(0, dispatches.get());
        } else {
            assertEquals(1, dispatches.get()); // Mobile UPnP callbacks remain unaffected.
        }
    }

    @Test public void pairedCommandsReachDispatcherAndRevocationBlocksThem() throws Exception {
        assertTrue(DlnaPairing.SESSION.claim(DlnaPairing.SESSION.open(), "192.168.1.2"));
        AtomicInteger dispatches = new AtomicInteger();
        runRequest("POST", dispatches);
        assertEquals(1, dispatches.get());
        DlnaPairing.SESSION.revoke();
        runRequest("POST", dispatches);
        assertEquals(ProductCore.getDeviceType() == 0 ? 1 : 2, dispatches.get());
    }

    @Test public void discoveryRemainsAvailableWithoutPairing() throws Exception {
        AtomicInteger dispatches = new AtomicInteger();
        runRequest("GET", dispatches);
        assertEquals(1, dispatches.get());
    }

    private String runRequest(String method, AtomicInteger dispatches) throws Exception {
        // In-memory socket double: never opens a socket or contacts a device.
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] request = (method + " /upnp/control HTTP/1.1\r\nX-Forwarded-For: 127.0.0.1\r\nRemote-Addr: 127.0.0.1\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        Socket socket = new Socket() {
            @Override public InputStream getInputStream() { return new ByteArrayInputStream(request); }
            @Override public OutputStream getOutputStream() { return output; }
            @Override public InetAddress getInetAddress() {
                try { return InetAddress.getByAddress(new byte[]{(byte)192, (byte)168, 1, 2}); }
                catch (Exception e) { throw new AssertionError(e); }
            }
        };
        ProtocolFactory factory = (ProtocolFactory) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{ProtocolFactory.class}, (proxy, called, args) -> {
                    if (called.getName().equals("createReceivingSync")) dispatches.incrementAndGet();
                    return null;
                });
        Class<?> stream = Class.forName("com.wuming.android.tv.dlna.SocketHttpStreamServer$SocketUpnpStream");
        Constructor<?> constructor = stream.getDeclaredConstructor(ProtocolFactory.class, Socket.class);
        constructor.setAccessible(true);
        ((Runnable) constructor.newInstance(factory, socket)).run();
        return output.toString(StandardCharsets.US_ASCII);
    }
}
