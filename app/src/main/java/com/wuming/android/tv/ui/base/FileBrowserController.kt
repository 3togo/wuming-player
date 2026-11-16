package com.wuming.android.tv.ui.base

import com.github.catvod.utils.Path
import java.io.File
import java.io.IOException

/** Shared filesystem navigation; views retain their own scroll and focus behavior. */
class FileBrowserController @JvmOverloads constructor(
    root: File,
    private val extensionFilter: String? = null,
    private val newestFirst: Boolean = false,
) {
    private val root = canonical(root, root)
    private var current = this.root

    fun openInitial(requestedPath: String?): File {
        if (requestedPath == null) return open(root)
        val requested = canonical(File(requestedPath), root)
        return open(if (isInsideRoot(requested) && requested.isDirectory) requested else root)
    }

    fun open(directory: File): File {
        val candidate = canonical(directory, root)
        current = if (isInsideRoot(candidate) && candidate.isDirectory) candidate else root
        return current
    }

    fun entries(): List<File> {
        val all = Path.list(current)
        val filtered = if (extensionFilter == null) all
        else all.filter { it.isDirectory || it.name.endsWith(extensionFilter) }
        if (!newestFirst) return filtered
        // Directories keep their place; files are listed newest modification first.
        return filtered.sortedWith(
            compareByDescending<File> { it.isDirectory }
                .thenByDescending { if (it.isDirectory) 0L else it.lastModified() }
                .thenByDescending { it.name }
        )
    }

    fun canGoBack(): Boolean = root != current

    fun goBack(): File = open(if (canGoBack()) current.parentFile!! else root)

    fun current(): File = current

    private fun isInsideRoot(file: File): Boolean = file == root || file.path.startsWith(root.path + File.separator)

    companion object {
        private fun canonical(
            file: File,
            fallback: File,
        ): File =
            try {
                file.canonicalFile
            } catch (_: IOException) {
                fallback.absoluteFile
            }
    }
}
