package com.wuming.tv.core.cloud

import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class CloudStorageEngineTest {
    private class StatusFailure(val status: Int) : IOException()
    private val statuses = OpenDriveFailurePolicy.StatusPort { error ->
        (error as? StatusFailure)?.let { OpenDriveFailurePolicy.Status(it.status, 7) }
    }

    private class Provider(private val key: String = "quark") : CloudStorageProvider {
        var loggedOut = false
        var failure: Exception? = null
        var authenticated = true
        var parent = ""
        val items = mutableListOf(OpenDriveItem("one", "One", false, 1))
        override fun id() = key
        override fun displayName() = "Fixture"
        override fun available() = true
        override fun availabilityMessage() = ""
        override fun authenticated() = authenticated
        override fun beginLogin() = OpenDriveLoginChallenge("session", "fixture", 0)
        override fun pollLogin(sessionId: String) = OpenDriveLoginState.WAITING
        override fun logout() { loggedOut = true }
        override fun list(parentId: String): List<OpenDriveItem> { failure?.let { throw it }; parent = parentId; return items }
        override fun resolve(itemId: String): OpenDrivePlayback { failure?.let { throw it }; return OpenDrivePlayback("https://media.example/fixture.mp4", emptyMap(), 0) }
    }

    @Test fun registryRejectsInvalidAndDuplicateIdentitiesWithoutExposingUnknownIds() {
        assertThrows(IllegalArgumentException::class.java) { CloudStorageEngine(listOf(Provider("BAD"))) }
        assertThrows(IllegalArgumentException::class.java) { CloudStorageEngine(listOf(Provider(), Provider())) }
        val engine = CloudStorageEngine(listOf(Provider()))
        val error = assertThrows(IllegalArgumentException::class.java) { engine.provider("private-untrusted-id") }
        assertEquals("Unknown OpenDrive provider", error.message)
        assertThrows(UnsupportedOperationException::class.java) { (engine.providers() as MutableList<Provider>).clear() }
    }

    @Test fun listingNormalizesRootsAndSnapshotsResultsWithoutMutatingProviderState() {
        val provider = Provider()
        val engine = CloudStorageEngine(listOf(provider))
        val listed = engine.list("quark", " ")
        assertEquals("root", provider.parent)
        provider.items.clear()
        assertEquals(1, listed.size)
        assertThrows(UnsupportedOperationException::class.java) { (listed as MutableList<OpenDriveItem>).clear() }
    }

    @Test fun authAndHttpFailuresNeverLogoutOrRetryTheProvider() {
        val provider = Provider()
        val engine = CloudStorageEngine(listOf(provider), statuses)
        provider.authenticated = false
        assertEquals(OpenDriveFailure.Reason.AUTH_REQUIRED,
            assertThrows(OpenDriveFailure::class.java) { engine.resolve("quark", "one") }.reason)
        provider.authenticated = true
        for ((status, reason) in listOf(401 to OpenDriveFailure.Reason.AUTH_REQUIRED,
            403 to OpenDriveFailure.Reason.ACCESS_DENIED, 429 to OpenDriveFailure.Reason.PROVIDER_ERROR,
            503 to OpenDriveFailure.Reason.NETWORK)) {
            provider.failure = StatusFailure(status)
            val error = assertThrows(OpenDriveFailure::class.java) { engine.resolve("quark", "one") }
            assertEquals(reason, error.reason)
            assertEquals(status, error.httpStatus)
            assertEquals(7L, error.apiCode)
            assertFalse(provider.loggedOut)
        }
        assertThrows(IllegalStateException::class.java) { provider.failure = StatusFailure(401); engine.list("quark", "root") }
        assertFalse(provider.loggedOut)
    }

    @Test fun cancellationTypedFailuresAndProxyParametersKeepTheirContracts() {
        val provider = Provider()
        val engine = CloudStorageEngine(listOf(provider), statuses)
        val cancel = CancellationException("fixture")
        provider.failure = cancel
        assertSame(cancel, assertThrows(CancellationException::class.java) { engine.resolve("quark", "one") })
        val typed = OpenDriveFailure("quark", OpenDriveFailure.Reason.QUOTA_EXHAUSTED)
        provider.failure = typed
        assertSame(typed, assertThrows(OpenDriveFailure::class.java) { engine.resolve("quark", "one") })
        val params = mutableMapOf("provider" to "quark", "value" to "fixture")
        engine.proxy(params) { found, copied ->
            assertSame(provider, found)
            assertThrows(UnsupportedOperationException::class.java) { (copied as MutableMap<String, String>)["value"] = "changed" }
        }
        assertEquals("fixture", params["value"])
    }
}
