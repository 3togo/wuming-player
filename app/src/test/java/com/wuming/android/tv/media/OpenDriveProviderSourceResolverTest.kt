package com.wuming.android.tv.media

import com.wuming.android.tv.cloud.opendrive.OpenDriveEngine
import com.wuming.android.tv.cloud.opendrive.OpenDriveProvider
import com.wuming.tv.core.cloud.OpenDriveItem
import com.wuming.tv.core.cloud.OpenDriveLoginChallenge
import com.wuming.tv.core.cloud.OpenDriveLoginState
import com.wuming.tv.core.cloud.OpenDrivePlayback
import com.wuming.tv.core.media.MediaSourceDescriptor
import com.wuming.tv.core.media.OpenDriveProviderIdentityCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class OpenDriveProviderSourceResolverTest {

    private class FakeProvider(
        private val id: String = "quark",
        private val available: Boolean = true,
        private val authenticated: Boolean = true,
        private val failure: Exception? = null,
        private val playback: OpenDrivePlayback =
            OpenDrivePlayback("https://cdn.example/play", mapOf("Cookie" to "sid=1"), 1234L),
    ) : OpenDriveProvider {
        var resolvedHandle: String? = null

        override fun id(): String = id
        override fun displayName(): String = "Fake"
        override fun available(): Boolean = available
        override fun availabilityMessage(): String = "provider is offline"
        override fun authenticated(): Boolean = authenticated
        override fun beginLogin(): OpenDriveLoginChallenge = throw UnsupportedOperationException()
        override fun pollLogin(sessionId: String): OpenDriveLoginState = OpenDriveLoginState.FAILED
        override fun list(parentId: String): List<OpenDriveItem> = emptyList()
        override fun logout() {}

        @Throws(Exception::class)
        override fun resolve(itemId: String): OpenDrivePlayback {
            resolvedHandle = itemId
            if (failure != null) throw failure
            return playback
        }
    }

    private fun descriptor(
        locator: Map<String, String>,
        adapterId: String = OpenDriveProviderIdentityCodec.ADAPTER_ID,
        version: Int = 1,
    ) = MediaSourceDescriptor(
        adapterId,
        version,
        locator,
        "sha256:" + "0".repeat(64),
        100,
        true,
    )

    private fun resolver(provider: OpenDriveProvider) =
        OpenDriveProviderSourceResolver(OpenDriveEngine(listOf(provider)))

    @Test
    fun advertisesTheOpenDriveAdapterId() {
        assertEquals(
            OpenDriveProviderIdentityCodec.ADAPTER_ID,
            OpenDriveProviderSourceResolver(null).adapterId(),
        )
    }

    @Test
    fun handsThePlayerTheResolvedUrlHeadersAndExpiry() {
        val provider = FakeProvider()
        val target = resolver(provider)
            .resolve(descriptor(mapOf("provider" to "quark", "handle" to "fid-1")))
        assertEquals("https://cdn.example/play", target.urlAccessor())
        assertEquals("sid=1", target.headersAccessor()["Cookie"])
        assertEquals(1234L, target.expiresAtEpochMsAccessor())
        assertEquals("fid-1", provider.resolvedHandle)
    }

    @Test
    fun neverReportsAnExpiryInThePast() {
        val target = resolver(FakeProvider(playback = OpenDrivePlayback("https://cdn.example/x", null, -5L)))
            .resolve(descriptor(mapOf("provider" to "quark", "handle" to "fid-1")))
        assertEquals(0L, target.expiresAtEpochMsAccessor())
        assertTrue(target.headersAccessor().isEmpty())
    }

    @Test
    fun refusesAWrongLocatorVersion() {
        val other = descriptor(mapOf("provider" to "quark", "handle" to "fid-1"), version = 2)
        assertThrows(IllegalArgumentException::class.java) { resolver(FakeProvider()).resolve(other) }
    }

    @Test
    fun refusesAForeignAdapter() {
        val other = descriptor(
            mapOf("provider" to "quark", "handle" to "fid-1"),
            adapterId = "org.other.adapter",
        )
        assertThrows(IllegalArgumentException::class.java) { resolver(FakeProvider()).resolve(other) }
    }

    @Test
    fun refusesAnIncompleteLocator() {
        assertThrows(IllegalArgumentException::class.java) {
            resolver(FakeProvider()).resolve(descriptor(mapOf("provider" to "quark")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolver(FakeProvider()).resolve(descriptor(mapOf("handle" to "fid-1")))
        }
    }

    @Test
    fun unknownProviderHandleIsRejected() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            resolver(FakeProvider()).resolve(descriptor(mapOf("provider" to "uc", "handle" to "fid-1")))
        }
        assertTrue(error.message!!.contains("Unknown OpenDrive provider"))
    }

    @Test
    fun anUnavailableProviderStaysUnsupported() {
        assertThrows(UnsupportedOperationException::class.java) {
            resolver(FakeProvider(available = false))
                .resolve(descriptor(mapOf("provider" to "quark", "handle" to "fid-1")))
        }
    }

    @Test
    fun aSignedCheckFailureBecomesAFatalResolutionError() {
        val error = assertThrows(IllegalStateException::class.java) {
            resolver(FakeProvider(authenticated = false))
                .resolve(descriptor(mapOf("provider" to "quark", "handle" to "fid-1")))
        }
        assertTrue(error.message!!.contains("AUTH_REQUIRED"))
    }

    @Test
    fun aProviderNetworkFailureKeepsItsDescription() {
        val error = assertThrows(IllegalStateException::class.java) {
            resolver(FakeProvider(failure = IOException("signed url expired")))
                .resolve(descriptor(mapOf("provider" to "quark", "handle" to "fid-1")))
        }
        assertTrue(error.cause is IOException)
    }

    @Test
    fun aProgrammingErrorInAProviderIsNotMasked() {
        val provider = FakeProvider(failure = IllegalStateException("bad provider state"))
        val error = assertThrows(IllegalStateException::class.java) {
            resolver(provider).resolve(descriptor(mapOf("provider" to "quark", "handle" to "fid-1")))
        }
        assertEquals("bad provider state", error.message)
    }
}
