package com.wuming.tv.desktop.cloud

import com.wuming.tv.core.cloud.CloudDriveProviderKind
import com.wuming.tv.core.runtime.cloudlogin.CloudCredentialStore
import com.wuming.tv.core.runtime.cloudlogin.CloudLoginResponse
import com.wuming.tv.core.runtime.cloudlogin.CloudLoginTransport
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wuming.tv.core.catalog.*
import com.wuming.tv.core.cloud.*

class DesktopCloudPlaybackResolverTest {
    @Test
    fun `quark external episode is saved and resolved with account cookie`() {
        val credentials = MemoryCredentials("__pus=account-cookie")
        val transport = RecordingTransport()
        val resolver = DesktopCloudPlaybackResolver(credentials, transport, copies()) {}
        val payload = """{
            "pan":"quark","fid":"shared-file","share_fid_token":"file-token",
            "share_id":"share-id","stoken":"share-token"
        }""".trimIndent()

        val playback = resolver.resolve(CloudDriveProviderKind.QUARK, payload)

        assertTrue(playback.supported)
        assertEquals("https://download.quark.test/video.mp4", playback.url)
        assertEquals("__pus=account-cookie", playback.headers["Cookie"])
        assertEquals(
            listOf("GET file/sort", "POST share/sharepage/save", "GET task", "POST file/download"),
            transport.requests,
        )
        assertTrue(transport.bodies[0].contains("\"fid_list\":[\"shared-file\"]"))
    }

    @Test
    fun `quark playback asks for desktop account when cookie is absent`() {
        val transport = RecordingTransport()
        val playback = DesktopCloudPlaybackResolver(MemoryCredentials(""), transport, copies()) {}
            .resolve(CloudDriveProviderKind.QUARK, "{}")

        assertFalse(playback.supported)
        assertTrue(playback.message.contains("Accounts"))
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `cached A B A navigation still routes through cloud resolver and forwards cancellation`() {
        var detailCalls = 0
        var cancelled = false
        var closed = false
        val source = object : CatalogSourceExecutor, AutoCloseable {
            override val source = CatalogSource("source", "Source")
            override fun search(query: String) = emptyList<CatalogItem>()
            override fun detail(id: String): CatalogDetail {
                detailCalls++
                return CatalogDetail(lines = listOf(CatalogLine("quark", "夸克原",
                    listOf(CatalogEpisode(id, id)))))
            }
            override fun play(flag: String, id: String): CatalogPlayback = error("Cloud routing was bypassed")
            override fun cancel() { cancelled = true }
            override fun close() { closed = true }
        }
        val executor = cloudAwareExecutor(source,
            DesktopCloudPlaybackResolver(MemoryCredentials(""), RecordingTransport(), copies()) {})
        executor.detail("A")
        executor.detail("B")
        executor.detail("A")
        assertEquals(2, detailCalls)
        assertTrue(executor.play("quark", "A").message.contains("Accounts"))
        clearCatalogCache(executor)
        // Refresh invalidates metadata without making the already selected cloud episode unroutable.
        assertTrue(executor.play("quark", "A").message.contains("Accounts"))
        executor.detail("A")
        assertEquals(3, detailCalls)
        executor.cancel()
        assertTrue(cancelled)
        (executor as AutoCloseable).close()
        assertTrue(closed)
    }

    private fun copies() = SavedCopyRegistry(object : SavedCopyStore {
        private var records = emptyList<SavedCopyRecord>()
        override fun <T> locked(action: () -> T) = action()
        override fun read() = records
        override fun write(records: List<SavedCopyRecord>) { this.records = records.toList() }
    }, 1, { true })

    private class MemoryCredentials(initial: String) : CloudCredentialStore {
        private var value = initial
        override fun read(providerId: String) = value
        override fun write(providerId: String, credential: String) { value = credential }
        override fun remove(providerId: String) { value = "" }
    }

    private class RecordingTransport : CloudLoginTransport {
        val requests = mutableListOf<String>()
        val bodies = mutableListOf<String>()

        override fun get(url: String, query: Map<String, String>, headers: Map<String, String>): CloudLoginResponse {
            val path = url.substringAfter("/clouddrive/")
            requests += "GET $path"
            return when (path) {
                "file/sort" -> response("""{"data":{"list":[{"dir":true,"file_name":"TVBoxCache","fid":"cache-folder"}]}}""")
                "task" -> response("""{"data":{"status":2,"save_as":{"save_as_top_fids":["saved-file"]}}}""")
                else -> error("Unexpected GET $path")
            }
        }

        override fun postJson(url: String, json: String, headers: Map<String, String>): CloudLoginResponse {
            val path = url.substringAfter("/clouddrive/").substringBefore('?')
            requests += "POST $path"
            bodies += json
            return when (path) {
                "share/sharepage/save" -> response("""{"data":{"task_id":"save-task"}}""")
                "file/download" -> response("""{"data":[{"fid":"saved-file","download_url":"https://download.quark.test/video.mp4"}]}""")
                else -> error("Unexpected POST $path")
            }
        }

        override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>) =
            error("Unexpected form request")

        private fun response(json: String) = CloudLoginResponse(JsonParser.parseString(json).asJsonObject)
    }
}
