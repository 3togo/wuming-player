package com.wuming.android.tv.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Locks in the decode-failure contract of {@link OpenDriveQrHandoff#decodePayload}: a thrown
 * decode error must trigger the recovery callback (which resets the in-flight flag and re-arms
 * the scan) and yield a null payload — otherwise one bad QR frame silently kills scanning.
 */
public class OpenDriveQrHandoffTest {

    @Test
    public void returnsPayloadWhenDecodeSucceeds() {
        boolean[] failed = {false};
        String payload = OpenDriveQrHandoff.decodePayload(() -> "qr-payload", () -> failed[0] = true);
        assertEquals("qr-payload", payload);
        assertTrue("recovery must not run on success", !failed[0]);
    }

    @Test
    public void runsRecoveryAndReturnsNullWhenDecodeThrows() {
        boolean[] failed = {false};
        String payload = OpenDriveQrHandoff.decodePayload(() -> {
            throw new RuntimeException("bad frame");
        }, () -> failed[0] = true);
        assertNull(payload);
        assertTrue("recovery must run on decode failure", failed[0]);
    }

    @Test
    public void recoveryRunsEvenOnOutOfMemoryError() {
        boolean[] failed = {false};
        String payload = OpenDriveQrHandoff.decodePayload(() -> {
            throw new OutOfMemoryError();
        }, () -> failed[0] = true);
        assertNull(payload);
        assertTrue("recovery must run on OutOfMemoryError too", failed[0]);
    }
}
