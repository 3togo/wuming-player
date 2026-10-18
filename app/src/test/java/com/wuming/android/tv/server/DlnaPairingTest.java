package com.wuming.android.tv.server;

import org.junit.Test;
import static org.junit.Assert.*;

public class DlnaPairingTest {
    @Test public void unpairedPeersCanDiscoverButCannotControlOrSubscribe() {
        assertTrue(DlnaPairing.allowsTvRequest("GET", false));
        assertTrue(DlnaPairing.allowsTvRequest("HEAD", false));
        for (String method : new String[]{"POST", "SUBSCRIBE", "UNSUBSCRIBE", "NOTIFY", "M-POST", "post", "", "DELETE"}) {
            assertFalse(method, DlnaPairing.allowsTvRequest(method, false));
        }
    }

    @Test public void pairedPeerMayUseStandardControlAndEventMethods() {
        for (String method : new String[]{"POST", "SUBSCRIBE", "UNSUBSCRIBE"}) {
            assertTrue(DlnaPairing.allowsTvRequest(method, true));
        }
    }
}
