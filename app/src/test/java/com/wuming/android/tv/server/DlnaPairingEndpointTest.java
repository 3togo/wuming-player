package com.wuming.android.tv.server;

import com.wuming.android.tv.ProductCore;
import fi.iki.elonen.NanoHTTPD;
import org.junit.After;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.Map;
import static org.junit.Assert.*;

public class DlnaPairingEndpointTest {
    @After public void clear() { DlnaPairing.SESSION.revoke(); RemoteAccess.closePairingWindow(); }

    private NanoHTTPD.Response request(String path, NanoHTTPD.Method method, Map<String, String> headers) {
        NanoHTTPD.IHTTPSession session = (NanoHTTPD.IHTTPSession) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[]{NanoHTTPD.IHTTPSession.class}, (proxy, called, args) -> switch (called.getName()) {
                    case "getUri" -> path;
                    case "getMethod" -> method;
                    case "getHeaders" -> headers;
                    case "getRemoteIpAddress" -> "192.168.1.2";
                    default -> throw new AssertionError(called.getName());
                });
        // serve() only: no HTTP listener and no network/device access.
        return new Nano(0).serve(session);
    }

    @Test public void scanningPageCannotClaimByGetAndPostRequiresTheSecret() {
        DlnaPairing.SESSION.open();
        assertEquals(405, request("/dlna-pair", NanoHTTPD.Method.GET, Map.of()).getStatus().getRequestStatus());
        assertEquals(403, request("/dlna-pair", NanoHTTPD.Method.POST, Map.of()).getStatus().getRequestStatus());
        assertEquals(403, request("/pair", NanoHTTPD.Method.POST, Map.of()).getStatus().getRequestStatus());
        assertFalse(DlnaPairing.SESSION.allows("192.168.1.2"));
    }

    @Test public void claimUsesSocketPeerAndRejectsReplay() {
        String code = DlnaPairing.SESSION.open();
        Map<String, String> headers = Map.of("x-pairing-code", code, "x-forwarded-for", "192.168.1.99", "remote-addr", "192.168.1.99");
        int expected = ProductCore.getDeviceType() == 0 ? 200 : 403;
        assertEquals(expected, request("/dlna-pair", NanoHTTPD.Method.POST, headers).getStatus().getRequestStatus());
        assertEquals(ProductCore.getDeviceType() == 0, DlnaPairing.SESSION.allows("192.168.1.2"));
        assertFalse(DlnaPairing.SESSION.allows("192.168.1.99"));
        assertEquals(403, request("/dlna-pair", NanoHTTPD.Method.POST, headers).getStatus().getRequestStatus());
    }

    @Test public void remotePairingNoLongerOffersTokensToEveryoneDuringTheWindow() {
        RemoteAccess.openPairingWindow();
        assertEquals(405, request("/pair", NanoHTTPD.Method.GET, Map.of()).getStatus().getRequestStatus());
        assertEquals(403, request("/pair", NanoHTTPD.Method.POST, Map.of()).getStatus().getRequestStatus());
    }
}
