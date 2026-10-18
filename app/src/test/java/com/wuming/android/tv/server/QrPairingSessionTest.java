package com.wuming.android.tv.server;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class QrPairingSessionTest {
    private final AtomicLong clock = new AtomicLong(1000);
    private final QrPairingSession session = new QrPairingSession(clock::get);

    @Test public void closedAndUnknownPeersAreDenied() {
        assertFalse(session.allows("192.168.1.2"));
        assertFalse(session.claim("0".repeat(64), "192.168.1.2"));
        String code = session.open();
        assertFalse(session.claim(null, "192.168.1.2"));
        assertFalse(session.claim("invalid", "192.168.1.2"));
        assertFalse(session.claim(code, ""));
        assertFalse(session.claim("0".repeat(64), "192.168.1.2"));
        assertTrue(session.claim(code, "192.168.1.2"));
        assertTrue(session.allows("192.168.1.2"));
        assertFalse(session.allows("192.168.1.3"));
        assertFalse(session.allows(null));
    }

    @Test public void codeIsSingleUseAndNewPhoneReplacesOldGrant() {
        String code = session.open();
        assertTrue(code.matches("[0-9a-f]{64}"));
        assertTrue(session.claim(code, "phone1"));
        assertFalse(session.claim(code, "phone2"));
        assertTrue(session.claim(session.open(), "phone2"));
        assertFalse(session.allows("phone1"));
        assertTrue(session.allows("phone2"));
    }

    @Test public void refreshAndCloseInvalidatePendingCodesButKeepPairedPhone() {
        assertTrue(session.claim(session.open(), "phone"));
        String old = session.open();
        String fresh = session.open();
        assertNotEquals(old, fresh);
        assertFalse(session.claim(old, "other"));
        session.close();
        assertFalse(session.claim(fresh, "other"));
        assertTrue(session.allows("phone"));
    }

    @Test public void expiryHasAnExactBoundaryAndCannotBeExtendedByCommands() {
        String code = session.open();
        clock.addAndGet(QrPairingSession.QR_LIFETIME_MS);
        assertEquals("", session.code());
        assertFalse(session.claim(code, "phone"));
        assertTrue(session.claim(session.open(), "phone"));
        clock.addAndGet(QrPairingSession.GRANT_LIFETIME_MS - 1);
        assertTrue(session.allows("phone"));
        clock.incrementAndGet();
        assertFalse(session.allows("phone"));
        assertEquals("", session.pairedPeer());
    }

    @Test public void revocationAndRestartDiscardAllAccess() {
        assertTrue(session.claim(session.open(), "phone"));
        String pending = session.open();
        session.revoke();
        assertFalse(session.allows("phone"));
        assertFalse(session.claim(pending, "other"));
        assertTrue(session.claim(session.open(), "phone"));
        assertFalse(new QrPairingSession(clock::get).allows("phone"));
    }
}
