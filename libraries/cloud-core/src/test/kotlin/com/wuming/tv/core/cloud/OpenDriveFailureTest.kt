package com.wuming.tv.core.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OpenDriveFailureTest {
    @Test fun codesSurviveMessageBoundaryWithoutEchoingSensitiveDetails() {
        for (reason in OpenDriveFailure.Reason.entries) {
            val failure = OpenDriveFailure("uc", reason, 403, 41027, Exception("secret signed URL"))
            val decoded = OpenDriveFailure.fromMessage(failure.message)
            assertNotNull(decoded)
            assertEquals(reason, decoded!!.reason())
            assertEquals("uc", decoded.provider())
            assertFalse(failure.message!!.contains("secret"))
            assertEquals(403, failure.httpStatus())
            assertEquals(41027, failure.apiCode())
        }
    }

    @Test fun arbitraryMessagesCannotBecomeTypedErrors() {
        assertNull(OpenDriveFailure.fromMessage("HTTP 403 expired quota"))
        assertNull(OpenDriveFailure.fromMessage("OpenDriveFailure:uc:AUTH_REQUIRED:secret"))
        assertNull(OpenDriveFailure.fromMessage("OpenDriveFailure:secret:AUTH_REQUIRED"))
        assertNull(OpenDriveFailure.fromMessage("OpenDriveFailure:uc:unknown"))
        assertFalse(OpenDriveFailure("secret", OpenDriveFailure.Reason.NETWORK).message!!.contains("secret"))
    }
}
