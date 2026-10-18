package com.wuming.android.tv.packages

import com.google.gson.Gson
import com.wuming.android.tv.server.Nano
import com.wuming.android.tv.server.Server
import com.wuming.android.tv.server.impl.Process
import com.wuming.tv.core.source.packages.PackageCandidate
import com.wuming.tv.core.source.packages.SourcePackageInspector
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.util.UUID

/** Protected by Nano's existing paired remote-access gate. Inspection never executes providers. */
class PackageProcess @JvmOverloads constructor(
    private val library: com.wuming.tv.core.source.packages.SourcePackageLibrary? = null,
) : Process {
    /**
     * Resolved on first package request, not at construction: Nano builds every process for
     * each connection, and opening the Android store root here would pull `App.get()` into
     * contexts (and unit tests) that never serve a package route.
     */
    private val store: com.wuming.tv.core.source.packages.SourcePackageLibrary
        get() = library ?: AndroidSourcePackages.library
    private val gson = Gson()
    private var preview: List<PackageCandidate> = emptyList()
    private var previewToken = ""
    private var previewAt = 0L
    override fun isRequest(session: NanoHTTPD.IHTTPSession, url: String): Boolean = url.startsWith("/packages/")

    @Synchronized
    override fun doResponse(session: NanoHTTPD.IHTTPSession, url: String, files: Map<String, String>): NanoHTTPD.Response {
        return try {
            val params = Nano.params(session)
            if (previewAt != 0L && System.currentTimeMillis() - previewAt !in 0..600000) { preview = emptyList(); previewToken = "" }
            when {
                url == "/packages/list" -> Nano.json(gson.toJson(store.installed()))
                url == "/packages/inspect" -> {
                    require(session.method == NanoHTTPD.Method.POST)
                    val upload = files.values.firstOrNull() ?: error("Select a package ZIP")
                    preview = emptyList(); previewToken = ""
                    preview = File(upload).inputStream().use { SourcePackageInspector.inspect(SourcePackageInspector.archive(it), File(params["package"] ?: "Source package").nameWithoutExtension) }
                    require(preview.isNotEmpty()) { "No source package configuration found" }
                    previewToken = UUID.randomUUID().toString(); previewAt = System.currentTimeMillis()
                    Nano.json(gson.toJson(mapOf("token" to previewToken, "packages" to preview.mapIndexed { index, candidate ->
                        mapOf("index" to index, "name" to candidate.name, "sites" to candidate.sites.map { mapOf("key" to it.first, "name" to it.second) }, "errors" to candidate.errors, "warnings" to candidate.warnings, "enabledKeys" to store.initialSelection(candidate))
                    })))
                }
                url == "/packages/install" -> {
                    require(session.method == NanoHTTPD.Method.POST)
                    require(params["token"] == previewToken && previewToken.isNotBlank() && System.currentTimeMillis() - previewAt in 0..600000) { "Preview expired; inspect again" }
                    val candidate = preview[params.getValue("index").toInt()]
                    val selected = gson.fromJson(params.getValue("keys"), Array<String>::class.java).toList()
                    val installed = store.install(candidate, params.getValue("name"), selected)
                    Nano.json(gson.toJson(installed))
                }
                url == "/packages/rollback" -> {
                    require(session.method == NanoHTTPD.Method.POST)
                    Nano.json(gson.toJson(store.rollback(params.getValue("id"))))
                }
                url.startsWith("/packages/config/") -> {
                    require(session.method == NanoHTTPD.Method.GET)
                    val id = url.removePrefix("/packages/config/")
                    Nano.json(store.configuration(id, Server.get().getAddress("/packages/files/$id")))
                }
                url.startsWith("/packages/files/") -> {
                    require(session.method == NanoHTTPD.Method.GET)
                    val parts = url.removePrefix("/packages/files/").split('/', limit = 3)
                    val bytes = store.resource(parts[0], parts[1], parts[2])
                    NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/octet-stream", bytes.inputStream(), bytes.size.toLong())
                }
                else -> Nano.error(NanoHTTPD.Response.Status.NOT_FOUND, "Package route not found")
            }.also { it.addHeader("Cache-Control", "no-store") }
        } catch (failure: Exception) {
            Nano.error(NanoHTTPD.Response.Status.BAD_REQUEST, failure.message ?: "Package operation failed")
        }
    }
}
