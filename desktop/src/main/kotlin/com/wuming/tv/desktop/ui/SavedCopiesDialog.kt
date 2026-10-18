package com.wuming.tv.desktop.ui

import com.wuming.tv.core.cloud.SavedCopyRecord
import com.wuming.tv.core.cloud.SavedCopyReview
import com.wuming.tv.desktop.cloud.DesktopCloudPlaybackResolver
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dialog
import java.awt.FlowLayout
import java.time.Instant
import javax.swing.*

/** Review precedes mutation; the registry rechecks the exact records when an action runs. */
internal object SavedCopiesDialog {
    fun show(parent: Component, resolver: DesktopCloudPlaybackResolver) {
        val dialog = JDialog(SwingUtilities.getWindowAncestor(parent), "Quark saved copies", Dialog.ModalityType.APPLICATION_MODAL)
        val text = JTextArea(18, 80).apply { isEditable = false }
        val status = JLabel("Loading saved copies…")
        val refresh = JButton("Refresh")
        val reconcile = JButton("Reconcile pending saves")
        val clean = JButton("Clean eligible copies…")
        val forget = JButton("Forget old or uncertain records…")
        val controls = listOf(refresh, reconcile, clean, forget)
        var reviewed = emptyList<SavedCopyReview>()
        var closed = false
        var worker: SwingWorker<Pair<String, List<SavedCopyReview>>, Void>? = null
        fun load(message: String = "", operation: () -> Unit = {}) {
            controls.forEach { it.isEnabled = false }
            status.text = "Working…"
            worker = object : SwingWorker<Pair<String, List<SavedCopyReview>>, Void>() {
                override fun doInBackground(): Pair<String, List<SavedCopyReview>> {
                    operation()
                    return message to resolver.reviewCopies()
                }
                override fun done() {
                    if (closed) return
                    runCatching { get() }.onSuccess { (result, rows) ->
                        reviewed = rows
                        text.text = buildString {
                            appendLine("${rows.size}/128 tracked saves. Protected copies cannot be cleaned.")
                            appendLine("Cleanup moves exact tracked files to the recycle bin; it does not purge the bin.")
                            appendLine("Uncertain means submission may have succeeded without a recorded task ID.")
                            appendLine()
                            rows.forEach {
                                appendLine("${it.state}: ${it.record.file.ifBlank { "pending save" }}  folder=${it.record.folder}  last used=${Instant.ofEpochMilli(it.record.updated)}")
                            }
                        }
                        status.text = result.ifBlank { "Review the records before choosing an action." }
                    }.onFailure { error ->
                        reviewed = emptyList()
                        status.text = error.cause?.message ?: error.message ?: "Maintenance failed."
                    }
                    refresh.isEnabled = true
                    reconcile.isEnabled = reviewed.any { it.state == SavedCopyReview.State.PENDING }
                    clean.isEnabled = reviewed.any { it.state == SavedCopyReview.State.ELIGIBLE }
                    forget.isEnabled = reviewed.any { it.state in setOf(SavedCopyReview.State.OTHER_SESSION, SavedCopyReview.State.UNCERTAIN) }
                }
            }.also { it.execute() }
        }
        fun confirm(records: List<SavedCopyRecord>, message: String): Boolean = JOptionPane.showConfirmDialog(
            dialog, "$message\n\n" + records.joinToString("\n") { "${it.file.ifBlank { "uncertain save" }} in ${it.folder}" },
            "Review ${records.size} records", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE,
        ) == JOptionPane.OK_OPTION
        refresh.addActionListener { load() }
        reconcile.addActionListener {
            load("Pending task reconciliation completed; unresolved tasks remain listed.") { resolver.reconcileCopies() }
        }
        clean.addActionListener {
            val selection = reviewed.filter { it.state == SavedCopyReview.State.ELIGIBLE }.map { it.record }
            if (selection.isNotEmpty() && confirm(selection, "Move these exact copies to Quark's recycle bin?\nChanged or newly protected records will be retained.")) {
                load("Cleanup completed. Refreshed records show anything retained.") { resolver.cleanCopies(selection) }
            }
        }
        forget.addActionListener {
            val selection = reviewed.filter { it.state in setOf(SavedCopyReview.State.OTHER_SESSION, SavedCopyReview.State.UNCERTAIN) }.map { it.record }
            if (selection.isNotEmpty() && confirm(selection,
                    "Forget only these LOCAL records? Cloud files will not be deleted.\nFuture automatic cleanup will no longer track them.\nFor an uncertain save, check Quark first: retrying afterward may create another copy.\nLive-process records remain protected.")) {
                load("Local-record review completed; protected records were retained. No cloud files were deleted.") { resolver.forgetCopies(selection) }
            }
        }
        dialog.add(JScrollPane(text), BorderLayout.CENTER)
        dialog.add(JPanel(BorderLayout()).apply {
            add(status, BorderLayout.NORTH)
            add(JPanel(FlowLayout()).apply { controls.forEach(::add) }, BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
        dialog.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        dialog.addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosed(event: java.awt.event.WindowEvent) { closed = true; worker?.cancel(true) }
        })
        dialog.pack()
        dialog.setLocationRelativeTo(parent)
        load()
        dialog.isVisible = true
    }
}
