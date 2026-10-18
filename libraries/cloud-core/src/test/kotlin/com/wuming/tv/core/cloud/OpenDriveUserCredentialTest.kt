package com.wuming.tv.core.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** JVM unit tests for the non-netdisk credential model invariants. */
class OpenDriveUserCredentialTest {

    @Test
    fun buildsWithRequiredFieldsAndExposesAccessors() {
        val cred = OpenDriveUserCredential(
            "danmaku",
            "My Danmaku",
            "https://api.lmteam.com",
            UserCredentialMethod.COOKIE,
            0L,
        )
        assertEquals("danmaku", cred.serviceId)
        assertEquals("My Danmaku", cred.displayName)
        assertEquals("https://api.lmteam.com", cred.baseUrl)
        assertEquals(UserCredentialMethod.COOKIE, cred.method)
        assertEquals(0L, cred.expiresAtEpochMillis)
    }

    @Test
    fun blankServiceIdIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            OpenDriveUserCredential("  ", "name", "https://h", UserCredentialMethod.COOKIE, 0L)
        }
    }

    @Test
    fun blankDisplayNameIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            OpenDriveUserCredential("id", "", "https://h", UserCredentialMethod.COOKIE, 0L)
        }
    }

    @Test
    fun emptyBaseUrlAndPasswordMethodAreAllowed() {
        val cred = OpenDriveUserCredential("id", "name", "", UserCredentialMethod.PASSWORD, 0L)
        assertEquals("", cred.baseUrl)
        assertEquals(UserCredentialMethod.PASSWORD, cred.method)
    }

    @Test
    fun serviceIdPatternAllowsLowercaseDigitsDashUnderscore() {
        val cred = OpenDriveUserCredential("ct_look-1", "ct", "https://h", UserCredentialMethod.OAUTH_TOKEN, 0L)
        assertEquals("ct_look-1", cred.serviceId)
        assertEquals(UserCredentialMethod.OAUTH_TOKEN, cred.method)
    }
}
