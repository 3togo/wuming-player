package com.wuming.tv.core.runtime.cloudlogin

import com.wuming.tv.core.cloud.*
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class QuarkPlaybackResolverTest {
    private val payload = """{"fid":"source-file","share_fid_token":"token","share_id":"share","stoken":"share-token"}"""
    private class Credentials : CloudCredentialStore {
        var cookie = "__pus=account"
        override fun read(providerId: String) = cookie
        override fun write(providerId: String, credential: String) { cookie = credential }
        override fun remove(providerId: String) { cookie = "" }
    }
    private class Store : SavedCopyStore {
        var records = emptyList<SavedCopyRecord>()
        override fun <T> locked(action: () -> T) = action()
        override fun read() = records
        override fun write(records: List<SavedCopyRecord>) { this.records = records.toList() }
    }
    private class Transport : CloudLoginTransport {
        var saves = 0
        var downloads = 0
        var polls = 0
        var statuses = listOf(1, 2)
        var failDownload = false
        var downloadCookies = emptyList<String>()
        var downloadBody = """{"data":[{"fid":"saved","download_url":"https://example.test/video"}]}"""
        var beforeDownload: () -> Unit = {}
        private fun response(json: String) = CloudLoginResponse(JsonParser.parseString(json).asJsonObject)
        override fun get(url: String, query: Map<String, String>, headers: Map<String, String>): CloudLoginResponse =
            when (url.substringAfter("/clouddrive/")) {
                "file/sort" -> if (query["pdir_fid"] == "0")
                    response("""{"data":{"list":[{"dir":true,"file_name":"TVBoxCache","fid":"folder"}]}}""")
                    else response("""{"data":{"list":[{"fid":"saved"}]}}""")
                "task" -> {
                    val status = statuses.getOrElse(polls++) { statuses.last() }
                    response("""{"data":{"status":$status,"save_as":{"save_as_top_fids":["saved"]}}}""")
                }
                else -> error("Unexpected GET")
            }
        override fun postJson(url: String, json: String, headers: Map<String, String>): CloudLoginResponse =
            when (url.substringAfter("/clouddrive/").substringBefore('?')) {
                "share/sharepage/save" -> { saves++; response("""{"data":{"task_id":"task"}}""") }
                "file/download" -> {
                    downloads++; beforeDownload()
                    if (failDownload) response("""{"code":123,"message":"private upstream detail"}""")
                    else response(downloadBody).copy(setCookies = downloadCookies)
                }
                else -> error("Unexpected POST")
            }
        override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): CloudLoginResponse = error("form")
    }
    @Test fun waitsForCompletionThenReusesOwnedCopyAcrossResolverInstances() {
        val store = Store()
        val credentials = Credentials()
        val transport = Transport()
        fun resolve() = QuarkPlaybackResolver(credentials, transport, SavedCopyRegistry(store, 1, { true })) {}
            .resolve(CloudDriveProviderKind.QUARK, payload)
        assertTrue(resolve().supported)
        assertEquals(2, transport.polls)
        assertTrue(resolve().supported)
        assertEquals(1, transport.saves)
        assertEquals(2, transport.downloads)
        assertEquals(1, store.records.size)
    }
    @Test fun failedTaskWithDestinationDoesNotDownloadOrRecordSuccess() {
        val transport = Transport().apply { statuses = listOf(3) }
        val store = Store()
        val result = QuarkPlaybackResolver(Credentials(), transport, SavedCopyRegistry(store, 1, { true })) {}
            .resolve(CloudDriveProviderKind.QUARK, payload)
        assertFalse(result.supported)
        assertEquals(0, transport.downloads)
        assertTrue(store.records.isEmpty())
    }
    @Test fun downloadFailureRetainsTrackedCopyForRetryWithoutAnotherSave() {
        val transport = Transport().apply { failDownload = true }
        val store = Store()
        val resolver = QuarkPlaybackResolver(Credentials(), transport, SavedCopyRegistry(store, 1, { true })) {}
        val failed = resolver.resolve(CloudDriveProviderKind.QUARK, payload)
        assertFalse(failed.supported)
        assertFalse(failed.message.contains("private upstream detail"))
        assertEquals(1, store.records.size)
        transport.failDownload = false
        assertTrue(resolver.resolve(CloudDriveProviderKind.QUARK, payload).supported)
        assertEquals(1, transport.saves)
    }
    @Test fun accountSwitchDuringResponseCannotPublishOldAccountPlayback() {
        val credentials = Credentials()
        val transport = Transport().apply { beforeDownload = { credentials.cookie = "__pus=another-account" } }
        val resolver = QuarkPlaybackResolver(credentials, transport, SavedCopyRegistry(Store(), 1, { true })) {}
        assertFalse(resolver.resolve(CloudDriveProviderKind.QUARK, payload).supported)
        assertEquals("__pus=another-account", credentials.cookie)
    }

    @Test fun downloadMatchesRequestedFileAndRetainsSigningCookieAcrossRotation() {
        val credentials = Credentials()
        val transport = Transport().apply {
            downloadBody = """{"data":[{"fid":"other","download_url":"https://example.test/wrong"},{"fid":"saved","download_url":"https://example.test/right"}]}"""
            downloadCookies = listOf("__pus=rotated; Secure")
        }
        val resolver = QuarkPlaybackResolver(credentials, transport, SavedCopyRegistry(Store(), 1, { true })) {}
        val playback = resolver.resolve(CloudDriveProviderKind.QUARK, payload)
        assertTrue(playback.supported)
        assertEquals("https://example.test/right", playback.url)
        assertEquals("__pus=account", playback.headers["Cookie"])
        assertEquals("__pus=rotated", credentials.cookie)
    }

    @Test fun missingMatchingDownloadNeverPlaysAnotherFile() {
        val transport = Transport().apply {
            downloadBody = """{"data":[{"fid":"other","download_url":"https://example.test/wrong"}]}"""
        }
        assertFalse(QuarkPlaybackResolver(Credentials(), transport, SavedCopyRegistry(Store(), 1, { true })) {}
            .resolve(CloudDriveProviderKind.QUARK, payload).supported)
    }

    @Test fun restartsResumePersistedTaskWithoutSubmittingAgain() {
        val transport = Transport()
        val store = Store()
        val credentials = Credentials()
        try {
            val first = QuarkPlaybackResolver(credentials, transport, SavedCopyRegistry(store, 1, { false })) {
                throw InterruptedException("restart")
            }
            assertFalse(first.resolve(CloudDriveProviderKind.QUARK, payload).supported)
            assertEquals("task", store.records.single().task)
            assertEquals("", store.records.single().file)
        } finally { Thread.interrupted() }
        val restarted = QuarkPlaybackResolver(credentials, transport, SavedCopyRegistry(store, 2, { false })) {}
        assertTrue(restarted.resolve(CloudDriveProviderKind.QUARK, payload).supported)
        assertEquals(1, transport.saves)
        assertEquals("saved", store.records.single().file)
        assertNull(store.records.single().task)
    }

    @Test fun interruptedPollingPreservesCancellationAndNeverDownloads() {
        val transport = Transport()
        try {
            val resolver = QuarkPlaybackResolver(Credentials(), transport, SavedCopyRegistry(Store(), 1, { true })) {
                throw InterruptedException("cancelled")
            }
            assertFalse(resolver.resolve(CloudDriveProviderKind.QUARK, payload).supported)
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(0, transport.downloads)
        } finally { Thread.interrupted() }
    }
}
