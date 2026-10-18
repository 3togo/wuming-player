package com.wuming.tv.core.source.packages

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Filesystem port shared by Android and desktop: immutable version directories and an
 * atomically replaced selection index.
 *
 * [restrictRootPermissions] tightens the store directory to owner-only access on file
 * systems that expose POSIX attributes. The desktop client enables it because its store
 * lives outside an application-private sandbox; Android already gets that protection from
 * `filesDir` and keeps the default.
 */
class FilePackageStorage(
    private val root: File,
    private val restrictRootPermissions: Boolean = false,
) : SourcePackageStorage {

    private fun prepareRoot() {
        root.mkdirs()
        if (restrictRootPermissions &&
            root.toPath().fileSystem.supportedFileAttributeViews().contains("posix")
        ) {
            Files.setPosixFilePermissions(root.toPath(), PosixFilePermissions.fromString("rwx------"))
        }
    }

    private fun checked(id: String, version: String): File {
        require(id.matches(Regex("[a-f0-9]{32}")) && version.matches(Regex("[a-f0-9]{64}")))
        return File(root, "$id/$version")
    }

    override fun readIndex(): String? = File(root, "index.json").takeIf { it.isFile }?.readText()

    override fun publishIndex(json: String) {
        prepareRoot()
        val temporary = File.createTempFile("index-", ".tmp", root)
        try {
            java.io.FileOutputStream(temporary).use { it.write(json.toByteArray()); it.fd.sync() }
            Files.move(temporary.toPath(), File(root, "index.json").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    override fun writeVersion(id: String, version: String, files: Map<String, ByteArray>) {
        prepareRoot()
        val destination = checked(id, version)
        if (destination.isDirectory) {
            val existing = readVersion(id, version)
            require(existing.keys == files.keys && files.all { (name, data) -> existing[name]?.contentEquals(data) == true }) { "Stored package version changed" }
            return
        }
        destination.parentFile!!.mkdirs()
        val stage = Files.createTempDirectory(destination.parentFile!!.toPath(), "stage-").toFile()
        try {
            files.forEach { (name, data) ->
                SourcePackageInspector.safePath(name)
                val target = File(stage, name)
                target.parentFile!!.mkdirs()
                java.io.FileOutputStream(target).use { it.write(data); it.fd.sync() }
            }
            Files.move(stage.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally { stage.deleteRecursively() }
    }

    override fun readVersion(id: String, version: String): Map<String, ByteArray> {
        val directory = checked(id, version)
        require(directory.isDirectory) { "Package version is missing" }
        var total = 0L
        val files = linkedMapOf<String, ByteArray>()
        directory.walkTopDown().forEach { file ->
            require(!Files.isSymbolicLink(file.toPath())) { "Package symlinks are not allowed" }
            if (file.isFile) {
                require(files.size < SourcePackageInspector.MAX_FILES)
                val path = file.relativeTo(directory).invariantSeparatorsPath
                val bytes = readResource(id, version, path)
                total += bytes.size
                require(total <= SourcePackageInspector.MAX_TOTAL_BYTES)
                files[path] = bytes
            }
        }
        return files
    }

    override fun readResource(id: String, version: String, path: String): ByteArray {
        SourcePackageInspector.safePath(path)
        val directory = checked(id, version).canonicalFile
        val target = File(directory, path).canonicalFile
        require(target.path.startsWith(directory.path + File.separator) && target.isFile) { "Package resource is unavailable" }
        return target.inputStream().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16384)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(out.size().toLong() + count <= SourcePackageInspector.MAX_FILE_BYTES)
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
    }
}
