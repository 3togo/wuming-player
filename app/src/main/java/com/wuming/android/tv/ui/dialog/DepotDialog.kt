package com.wuming.android.tv.ui.dialog

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.github.catvod.net.OkHttp
import com.wuming.android.tv.App
import com.wuming.android.tv.R
import com.wuming.android.tv.databinding.DialogDepotBinding
import com.wuming.android.tv.databinding.ItemDepotSourceBinding
import com.wuming.android.tv.utils.DepotManager
import com.wuming.android.tv.utils.DialogUtil
import com.wuming.android.tv.utils.Notify
import com.wuming.android.tv.utils.Task
import com.wuming.android.tv.utils.TvAlertDialogBuilder
import com.wuming.android.tv.utils.TvDialogFocus
import com.wuming.android.tv.utils.Util
import com.wuming.tv.core.source.config.TvBoxWarehouse
import com.wuming.tv.core.source.config.TvBoxWarehouseChild
import com.wuming.tv.core.source.config.OfflineLinkCatalog
import com.wuming.tv.core.source.config.SourceBrowser
import com.wuming.tv.core.source.config.SourceBrowserEntry
import com.wuming.tv.core.source.config.SourceKind
import com.wuming.tv.core.source.config.SourceOrigin
import com.wuming.tv.core.source.config.ProtectedSourceUrl
import com.wuming.tv.core.source.config.SourceEditGate
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Collections
import java.util.Date
import java.util.Locale

open class DepotDialog private constructor(
    context: Context,
    private val launcher: DepotSourceLauncher?,
) {
    fun interface DepotSourceLauncher {
        fun launchConfigForUrl(
            context: Context,
            url: String,
            type: Int,
            name: String,
            editUrl: Boolean,
        )
    }

    private val binding = DialogDepotBinding.inflate(DialogUtil.inflater(context))
    private val dialog = TvAlertDialogBuilder(context, R.style.DarkDialogStyle).setView(binding.root).create()
    private var adapter: Adapter? = null
    private val warehouseCache = mutableMapOf<String, List<TvBoxWarehouseChild>>()
    private val loadingWarehouses = mutableSetOf<String>()
    private var browserChoices = emptyList<SourceBrowserEntry>()
    private var sourceInfoByUrl = emptyMap<String, DepotManager.SourceInfo>()
    private var testSource: DepotManager.SourceInfo? = null
    private var filterKind: SourceKind? = null
    private var filterOrigin: SourceOrigin? = null
    private var packageImport: com.wuming.android.tv.packages.SourcePackageDialog? = null
    private fun packageDialog(context: Context): com.wuming.android.tv.packages.SourcePackageDialog? {
        var owner = context
        while (owner is android.content.ContextWrapper && owner !is androidx.fragment.app.FragmentActivity) owner = owner.baseContext
        val activity = owner as? androidx.fragment.app.FragmentActivity ?: return null
        packageImport?.close()
        return com.wuming.android.tv.packages.SourcePackageDialog(activity) { initView(context) }.also { packageImport = it }
    }

    companion object {
        @JvmStatic fun create(context: Context): DepotDialog = DepotDialog(context, null)

        @JvmStatic fun create(
            context: Context,
            launcher: DepotSourceLauncher?,
        ): DepotDialog = DepotDialog(context, launcher)
    }

    init {
        dialog.setOnDismissListener { cleanup() }
        dialog.setOnCancelListener { cleanup() }
        binding.browseOfflineLinks.setOnClickListener { showSourceFilters(context) }
        binding.searchSources.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updateBrowserList(context)
            override fun afterTextChanged(s: Editable?) = Unit
        })
        binding.importPackage.setOnClickListener { packageDialog(context)?.open() }
        binding.rollbackPackage.setOnClickListener { packageDialog(context)?.rollback() }
        binding.close.setOnClickListener { dismiss() }
        initView(context)
    }

    private fun initView(context: Context) {
        try {
            val depot = DepotManager.loadDepot(context)
            val vodArray = depot.optJSONArray("vod")
            val liveArray = depot.optJSONArray("live")
            val sources = ArrayList<DepotManager.SourceInfo>()
            val warehouseUrls = mutableSetOf<String>()
            val localUrls = mutableSetOf<String>()
            val readmeUrls = DepotManager.getReadmeUrls()
            if (readmeUrls.isNotEmpty()) sources.add(
                DepotManager.SourceInfo(
                    context.getString(R.string.setting_depot_update),
                    readmeUrls[0],
                    "Test",
                    0,
                    0,
                    0,
                    0,
                ),
            )
            if (vodArray != null) {
                for (i in 0 until vodArray.length()) {
                    val item = vodArray.getJSONObject(i)
                    val name = item.optString("name", "")
                    if (TvBoxWarehouse.isLikelyIndex(name, item.optBoolean("warehouse", false)))
                        warehouseUrls.add(item.optString("url", ""))
                    sources.add(
                        DepotManager.SourceInfo(
                            item.optString("name", "Unknown"),
                            item.optString("url", ""),
                            "VOD",
                            item.optLong("lastVisit", 0),
                            item.optInt("count", 0),
                            item.optLong("ping", 0),
                            item.optInt("failCount", 0),
                        ),
                    )
                }
            }
            if (liveArray != null) {
                for (i in 0 until liveArray.length()) {
                    val item = liveArray.getJSONObject(i)
                    sources.add(
                        DepotManager.SourceInfo(
                            item.optString("name", "Unknown"),
                            item.optString("url", ""),
                            "Live",
                            item.optLong("lastVisit", 0),
                            item.optInt("count", 0),
                            item.optLong("ping", 0),
                            item.optInt("failCount", 0),
                        ),
                    )
                }
            }
            runCatching { com.wuming.android.tv.packages.AndroidSourcePackages.library.installed() }.getOrDefault(emptyList()).forEach { item ->
                val url = "package://${item.id}"
                localUrls.add(url)
                sources.add(DepotManager.SourceInfo("${item.name} (local)", url, "VOD", 0, 0, 0, 0))
            }
            sources.sortWith { a, b ->
                // Failed sources remain listed, below healthy sources.
                if ((a.failCount > 0) != (b.failCount > 0)) return@sortWith if (a.failCount > 0) 1 else -1
                if (a.failCount > 0 && b.failCount > 0 &&
                    a.failCount != b.failCount
                ) {
                    return@sortWith Integer.compare(a.failCount, b.failCount)
                }
                if (a.ping > 0 && b.ping > 0) {
                    val cmp = java.lang.Long.compare(a.ping, b.ping)
                    if (cmp != 0) return@sortWith cmp
                }
                if (a.ping > 0 && b.ping == 0L) return@sortWith -1
                if (a.ping == 0L && b.ping > 0) return@sortWith 1
                a.name.compareTo(b.name, ignoreCase = true)
            }
            val curated = sources.filter { it.type != "Test" && it.url.isNotBlank() }.map { info ->
                SourceBrowserEntry(info.name, info.url,
                    if (info.type == "Live") SourceKind.LIVE else SourceKind.VOD,
                    if (info.url in localUrls) SourceOrigin.LOCAL else SourceOrigin.CURATED,
                    info.url in warehouseUrls)
            }
            val screened = runCatching {
                context.assets.open("link_catalog.json").use { OfflineLinkCatalog.parse(it.readBytes()) }
            }.getOrDefault(emptyList()).flatMap { group ->
                group.entries.map { entry ->
                    val warehouse = entry.warehouse || TvBoxWarehouse.isLikelyIndex(entry.name, false)
                    if (warehouse) warehouseUrls.add(entry.url)
                    SourceBrowserEntry(entry.name, entry.url,
                        if (group.kind == "live") SourceKind.LIVE else SourceKind.VOD,
                        SourceOrigin.SCREENED, warehouse)
                }
            }
            browserChoices = SourceBrowser.merge(curated, screened)
            val infos = sources.associateBy { it.url }.toMutableMap()
            screened.forEach { entry ->
                infos.putIfAbsent(entry.url, DepotManager.SourceInfo(entry.name, entry.url,
                    if (entry.kind == SourceKind.LIVE) "Live" else "VOD", 0, 0, 0, 0))
            }
            sourceInfoByUrl = infos
            testSource = sources.firstOrNull { it.type == "Test" }
            if (browserChoices.isNotEmpty() || testSource != null) {
                adapter = Adapter(emptyList(), launcher,
                    browserChoices.filter { it.origin == SourceOrigin.SCREENED }.map { it.url }.toSet(),
                    browserChoices.filter { it.origin != SourceOrigin.LOCAL }.map { it.url }.toSet(),
                    { useContext, info -> useSource(useContext, info, info.url in warehouseUrls) },
                    { editContext, info -> promptSourceEdit(editContext, info) })
                binding.recycler.adapter = adapter
                updateBrowserList(context)
            }
        } catch (ignored: Exception) {
        }
    }

    private fun updateBrowserList(context: Context) {
        val query = binding.searchSources.text?.toString().orEmpty()
        val visible = SourceBrowser.filter(browserChoices, query, filterKind, filterOrigin)
            .mapNotNull { sourceInfoByUrl[it.url] }.toMutableList()
        if (query.isBlank() && filterKind == null && filterOrigin == null) testSource?.let { visible.add(0, it) }
        adapter?.replace(visible)
        binding.header.text = context.getString(R.string.setting_depot_header, visible.size,
            visible.count { it.type == "VOD" }, visible.count { it.type == "Live" },
            visible.count { it.ping > 0 })
    }

    private fun showSourceFilters(context: Context) {
        val labels = intArrayOf(R.string.setting_depot_filter_all, R.string.setting_depot_filter_vod,
            R.string.setting_depot_filter_live, R.string.setting_depot_filter_curated,
            R.string.setting_depot_filter_screened)
        val picker = TvAlertDialogBuilder(context, R.style.DarkDialogStyle)
            .setTitle(R.string.setting_depot_filter_title)
            .setItems(labels.map(context::getString).toTypedArray()) { _, index ->
                filterKind = when (index) { 1 -> SourceKind.VOD; 2 -> SourceKind.LIVE; else -> null }
                filterOrigin = when (index) { 3 -> SourceOrigin.CURATED; 4 -> SourceOrigin.SCREENED; else -> null }
                binding.browseOfflineLinks.setText(labels[index])
                updateBrowserList(context)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        picker.show()
        TvDialogFocus.list(picker)
    }

    open fun show() {
        dialog.show()
    }

    open fun dismiss() {
        dialog.dismiss()
    }

    private fun promptSourceEdit(context: Context, info: DepotManager.SourceInfo) {
        val field = EditText(context).apply {
            hint = context.getString(R.string.setting_depot_admin_password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        val padding = context.resources.getDimensionPixelSize(R.dimen.vs_16)
        val frame = LinearLayout(context).apply {
            setPadding(padding, padding, padding, padding)
            addView(field)
        }
        val picker = TvAlertDialogBuilder(context, R.style.DarkDialogStyle)
            .setTitle(R.string.setting_depot_edit)
            .setView(frame)
            .setPositiveButton(R.string.setting_depot_edit) { _, _ ->
                if (SourceEditGate.accepts(field.text.toString()))
                    launcher?.launchConfigForUrl(context, info.url, if (info.type == "Live") 1 else 0,
                        info.name, true)
                else Notify.show(context.getString(R.string.setting_depot_wrong_password))
                field.text?.clear()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        picker.show()
        TvDialogFocus.form(picker, field)
    }

    private fun useSource(context: Context, info: DepotManager.SourceInfo, warehouse: Boolean) {
        val open = launcher ?: return
        if (!warehouse) {
            open.launchConfigForUrl(context, info.url, 0, info.name, false)
            return
        }
        fun showChildren(children: List<TvBoxWarehouseChild>) {
            if (children.isEmpty()) {
                Notify.show(context.getString(R.string.setting_depot_warehouse_empty))
                return
            }
            val picker = TvAlertDialogBuilder(context, R.style.DarkDialogStyle)
                .setTitle(info.name)
                .setItems(children.map { it.name }.toTypedArray()) { _, index ->
                    val selected = children[index]
                    open.launchConfigForUrl(context, selected.url, 0, selected.name, false)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            picker.show()
            TvDialogFocus.list(picker)
        }
        warehouseCache[info.url]?.let { showChildren(it); return }
        if (!loadingWarehouses.add(info.url)) return
        Notify.show(context.getString(R.string.setting_depot_warehouse_loading))
        Task.execute {
            val result = runCatching {
                OkHttp.newCall(OkHttp.client(15000), info.url).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val bytes = response.body.bytes()
                    if (bytes.isEmpty()) error("Empty warehouse response")
                    TvBoxWarehouse.parseOrNull(bytes, info.url)
                }
            }
            App.post {
                loadingWarehouses.remove(info.url)
                if (dialog.isShowing) result.onSuccess { children ->
                    if (children == null) open.launchConfigForUrl(context, info.url, 0, info.name, false)
                    else {
                        warehouseCache[info.url] = children
                        showChildren(children)
                    }
                }.onFailure { Notify.show(it.message ?: context.getString(R.string.setting_depot_warehouse_empty)) }
            }
        }
    }

    private fun cleanup() {
        packageImport?.close()
        packageImport = null
        binding.recycler.adapter = null
        adapter = null
    }

    private class Adapter(
        private var sources: List<DepotManager.SourceInfo>,
        private val launcher: DepotSourceLauncher?,
        private val screenedUrls: Set<String>,
        private val bundledUrls: Set<String>,
        private val onUse: (Context, DepotManager.SourceInfo) -> Unit,
        private val onEdit: (Context, DepotManager.SourceInfo) -> Unit,
    ) : RecyclerView.Adapter<Adapter.ViewHolder>() {
        fun replace(updated: List<DepotManager.SourceInfo>) {
            sources = updated
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(
            parent: android.view.ViewGroup,
            viewType: Int,
        ): ViewHolder = ViewHolder(ItemDepotSourceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(
            holder: ViewHolder,
            position: Int,
        ) {
            val info = sources[position]
            holder.binding.name.text = info.name
            holder.binding.type.text = when {
                info.type == "Test" || info.url.startsWith("package://") -> info.type
                info.url in screenedUrls -> "${info.type} · ${holder.itemView.context.getString(R.string.setting_depot_source_screened)}"
                else -> "${info.type} · ${holder.itemView.context.getString(R.string.setting_depot_source_curated)}"
            }
            holder.binding.url.text = when {
                info.url in bundledUrls && ProtectedSourceUrl.hasCredential(info.url) ->
                    holder.itemView.context.getString(R.string.setting_depot_protected_source)
                info.url in bundledUrls -> {
                    val host = runCatching { java.net.URL(info.url).host }.getOrDefault("")
                    listOf(host, holder.itemView.context.getString(R.string.setting_depot_bundled_source))
                        .filter(String::isNotBlank).joinToString(" · ")
                }
                else -> info.url
            }
            holder.binding.ping.text = if (info.ping > 0) info.ping.toString() + "ms" else "-"
            holder.binding.count.text = if (info.count > 0) info.count.toString() else "-"
            holder.binding.lastVisit.text =
                if (info.lastVisit > 0) SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(info.lastVisit)) else "-"
            val isRealSource = info.type != "Test" && info.url.isNotEmpty()
            holder.itemView.setOnClickListener { v ->
                if (isRealSource) {
                    if (info.url in bundledUrls) onEdit(v.context, info)
                    else if (Util.copySilent(info.url)) {
                        Notify.show(v.context.getString(R.string.setting_depot_copied, info.name))
                    }
                }
            }
            // Open the flavor-specific config dialog prefilled with this source.
            if (isRealSource && launcher != null) {
                holder.binding.use.visibility = android.view.View.VISIBLE
                holder.binding.use.setOnClickListener { v ->
                    if (info.type == "Live") launcher.launchConfigForUrl(v.context, info.url, 1, info.name, false)
                    else onUse(v.context, info)
                }
            } else {
                holder.binding.use.visibility = android.view.View.GONE
            }
        }

        override fun getItemCount(): Int = sources.size

        class ViewHolder(
            val binding: ItemDepotSourceBinding,
        ) : RecyclerView.ViewHolder(binding.root)
    }
}
