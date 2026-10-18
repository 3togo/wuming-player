package com.wuming.tv.desktop.packages

import com.wuming.tv.core.source.packages.InstalledSourcePackage
import com.wuming.tv.core.source.packages.PackageCandidate
import com.wuming.tv.core.source.packages.SourcePackageInspector
import java.awt.BorderLayout
import java.awt.Component
import java.io.File
import java.nio.file.Files
import javax.swing.*

object PackageImportDialog {
    fun show(parent: Component, installed: (InstalledSourcePackage) -> Unit) {
        val chooser = JFileChooser().apply {
            dialogTitle = "Inspect a source package ZIP or folder"
            fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
        }
        if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return
        val file = chooser.selectedFile
        object : SwingWorker<List<PackageCandidate>, Void>() {
            override fun doInBackground(): List<PackageCandidate> {
                val files = if (file.isDirectory) readDirectory(file) else file.inputStream().use(SourcePackageInspector::archive)
                return SourcePackageInspector.inspect(files, file.nameWithoutExtension)
            }
            override fun done() {
                runCatching { get() }.onSuccess { candidates ->
                    if (candidates.isEmpty()) { error(parent, "No source configuration found"); return@onSuccess }
                    val chosen = if (candidates.size == 1) candidates.first() else {
                        val names = candidates.mapIndexed { i, c -> "${i + 1}. ${c.name} (${c.sites.size} sources; ${c.errors.size} errors)" }
                        val selected = JOptionPane.showInputDialog(parent, "Select a package to review", "Source packages", JOptionPane.PLAIN_MESSAGE, null, names.toTypedArray(), names.first()) ?: return@onSuccess
                        candidates[names.indexOf(selected)]
                    }
                    review(parent, chosen, installed)
                }.onFailure { error(parent, it.cause?.message ?: it.message ?: "Import failed") }
            }
        }.execute()
    }

    private fun review(parent: Component, candidate: PackageCandidate, installed: (InstalledSourcePackage) -> Unit) {
        val details = JTextArea((candidate.errors.map { "ERROR: $it" } + candidate.warnings).joinToString("\n")).apply {
            isEditable = false; lineWrap = true; wrapStyleWord = true; rows = 9; columns = 68
        }
        if (candidate.errors.isNotEmpty()) {
            JOptionPane.showMessageDialog(parent, JScrollPane(details), "Package needs repair", JOptionPane.WARNING_MESSAGE)
            return
        }
        val name = JTextField(candidate.name)
        val sources = JList(candidate.sites.map { "${it.second} [${it.first}]" }.toTypedArray()).apply {
            selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
            visibleRowCount = 10
            val initial = DesktopSourcePackages.library.initialSelection(candidate)
            selectedIndices = candidate.sites.indices.filter { candidate.sites[it].first in initial }.toIntArray()
        }
        val panel = JPanel(BorderLayout(8, 8)).apply {
            add(JPanel(BorderLayout()).apply {
                add(JLabel("Package name (same name updates the installed package)"), BorderLayout.NORTH)
                add(name, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(JScrollPane(details), BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply {
                add(JLabel("Select enabled sources (Ctrl/Shift to select). Runtime compatibility remains unverified."), BorderLayout.NORTH)
                add(JScrollPane(sources), BorderLayout.CENTER)
            }, BorderLayout.SOUTH)
        }
        if (JOptionPane.showConfirmDialog(parent, panel, "Inspect and install package", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return
        val selected = sources.selectedIndices.map { candidate.sites[it].first }
        val packageName = name.text
        object : SwingWorker<InstalledSourcePackage, Void>() {
            override fun doInBackground() = DesktopSourcePackages.library.install(candidate, packageName, selected)
            override fun done() {
                runCatching { get() }.onSuccess(installed).onFailure { error(parent, it.cause?.message ?: it.message ?: "Installation failed") }
            }
        }.execute()
    }

    internal fun readDirectory(root: File): Map<String, ByteArray> {
        var total = 0L
        val files = linkedMapOf<String, ByteArray>()
        root.walkTopDown().forEach { file ->
            require(!Files.isSymbolicLink(file.toPath())) { "Package symlinks are not allowed" }
            if (file.isFile) {
                require(files.size < SourcePackageInspector.MAX_FILES && file.length() <= SourcePackageInspector.MAX_FILE_BYTES) { "Package exceeds import limits" }
                val bytes = file.inputStream().use { it.readNBytes(SourcePackageInspector.MAX_FILE_BYTES + 1) }
                total += bytes.size
                require(bytes.size <= SourcePackageInspector.MAX_FILE_BYTES && total <= SourcePackageInspector.MAX_TOTAL_BYTES)
                files[file.relativeTo(root).invariantSeparatorsPath] = bytes
            }
        }
        return files
    }

    private fun error(parent: Component, message: String) = JOptionPane.showMessageDialog(parent, message, "Source package", JOptionPane.ERROR_MESSAGE)
}
