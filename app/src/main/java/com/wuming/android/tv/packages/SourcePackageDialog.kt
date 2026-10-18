package com.wuming.android.tv.packages

import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import com.wuming.android.tv.App
import com.wuming.android.tv.R
import com.wuming.android.tv.utils.*
import com.wuming.tv.core.source.packages.PackageCandidate
import com.wuming.tv.core.source.packages.SourcePackageInspector
import java.io.File
import java.util.UUID

/** Native file selection shares the same offline inspector/store as the paired browser. */
class SourcePackageDialog(private val activity: FragmentActivity, private val changed: () -> Unit) : AutoCloseable {
    private var closed = false
    private var job: AutoCloseable? = null
    private var activeDialog: AlertDialog? = null
    private val picker = activity.activityResultRegistry.register("source-package-${UUID.randomUUID()}", ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (!closed && result.resultCode == android.app.Activity.RESULT_OK && uri != null) {
            job = FileChooser.importFile(activity, uri, { path ->
                File(path).inputStream().use { SourcePackageInspector.inspect(SourcePackageInspector.archive(it)) }
            }, { candidates -> if (!closed) choose(candidates) })
        }
    }

    fun open() = FileChooser.from(picker).show("application/zip")

    private fun builder() = TvAlertDialogBuilder(activity, R.style.DarkDialogStyle)
    private fun show(dialog: AlertDialog, list: Boolean = false) {
        activeDialog = dialog
        dialog.setOnDismissListener { if (activeDialog === dialog) activeDialog = null }
        dialog.show()
        if (list) TvDialogFocus.list(dialog)
    }

    private fun choose(candidates: List<PackageCandidate>) {
        if (candidates.isEmpty()) { Notify.show(R.string.source_package_not_found); return }
        if (candidates.size == 1) { review(candidates.first()); return }
        show(builder().setTitle(R.string.source_package_import)
            .setItems(candidates.map { "${it.name} (${it.sites.size})" }.toTypedArray()) { _, index -> review(candidates[index]) }
            .setNegativeButton(android.R.string.cancel, null).create(), true)
    }

    private fun review(candidate: PackageCandidate) {
        val details = (candidate.errors.map { "ERROR: $it" } + candidate.warnings).joinToString("\n\n")
        val builder = builder().setTitle(candidate.name).setMessage(details).setNegativeButton(android.R.string.cancel, null)
        if (candidate.errors.isEmpty()) builder.setPositiveButton(R.string.source_package_select) { _, _ -> select(candidate) }
        show(builder.create())
    }

    private fun select(candidate: PackageCandidate) {
        val initial = AndroidSourcePackages.library.initialSelection(candidate)
        val checked = BooleanArray(candidate.sites.size) { candidate.sites[it].first in initial }
        show(builder().setTitle(R.string.source_package_select)
            .setMultiChoiceItems(candidate.sites.map { it.second }.toTypedArray(), checked) { _, index, value -> checked[index] = value }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val keys = candidate.sites.filterIndexed { index, _ -> checked[index] }.map { it.first }
                if (keys.isEmpty()) Notify.show(R.string.source_package_select) else name(candidate, keys)
            }.setNegativeButton(android.R.string.cancel, null).create(), true)
    }

    private fun name(candidate: PackageCandidate, keys: List<String>) {
        val field = EditText(DialogUtil.inflater(activity).context).apply {
            setSingleLine(true)
            setText(candidate.name)
            contentDescription = activity.getString(R.string.source_package_name)
            val inset = resources.getDimensionPixelSize(R.dimen.vs_16)
            setPadding(inset, inset, inset, inset)
        }
        val dialog = builder().setTitle(R.string.source_package_name).setMessage(R.string.source_package_name_hint).setView(field)
            .setPositiveButton(R.string.source_package_install) { _, _ ->
                val name = field.text.toString()
                Task.execute {
                    val result = runCatching { AndroidSourcePackages.library.install(candidate, name, keys) }
                    App.post {
                        if (!closed && !activity.isDestroyed) {
                            result.onSuccess { Notify.show(R.string.source_package_installed); changed() }
                                .onFailure { Notify.show(it.message ?: activity.getString(R.string.file_import_failed)) }
                        }
                    }
                }
            }.setNegativeButton(android.R.string.cancel, null).create()
        show(dialog)
        TvDialogFocus.form(dialog, field)
    }

    fun rollback() {
        val items = AndroidSourcePackages.library.installed().filter { it.previous != null }
        if (items.isEmpty()) { Notify.show(R.string.source_package_no_previous); return }
        show(builder().setTitle(R.string.source_package_rollback)
            .setItems(items.map { it.name }.toTypedArray()) { _, index ->
                Task.execute {
                    val result = runCatching { AndroidSourcePackages.library.rollback(items[index].id) }
                    App.post {
                        if (!closed && !activity.isDestroyed) {
                            result.onSuccess { Notify.show(R.string.source_package_restored); changed() }
                                .onFailure { Notify.show(it.message ?: activity.getString(R.string.file_import_failed)) }
                        }
                    }
                }
            }.setNegativeButton(android.R.string.cancel, null).create(), true)
    }

    override fun close() {
        closed = true
        job?.close()
        picker.unregister()
        activeDialog?.dismiss()
        activeDialog = null
    }
}
