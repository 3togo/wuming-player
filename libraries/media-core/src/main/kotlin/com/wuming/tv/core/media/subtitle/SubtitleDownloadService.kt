package com.wuming.tv.core.media.subtitle

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class SubtitleDownloadService {

    companion object {
        const val DEFAULT_MAX_DOWNLOAD_BYTES: Long = 10L * 1024L * 1024L
        const val DEFAULT_MAX_EXTRACTED_BYTES: Long = 20L * 1024L * 1024L
        const val DEFAULT_MAX_ARCHIVE_ENTRIES: Int = 32
        private val EXTENSIONS: Set<String> = setOf("srt", "ass", "ssa", "vtt", "sub")

        @JvmStatic
        fun supportedExtension(value: String?): String {
            val normalized = value?.lowercase()?.replace(".", "") ?: ""
            return if (EXTENSIONS.contains(normalized)) normalized else ""
        }

        @JvmStatic
        fun extension(value: String?): String {
            if (value == null) return ""
            val dot = value.lastIndexOf('.')
            return if (dot < 0) "" else supportedExtension(value.substring(dot + 1))
        }

        @JvmStatic
        fun isZip(data: ByteArray): Boolean =
            data.size >= 4 && data[0] == 'P'.code.toByte() && data[1] == 'K'.code.toByte() &&
                (data[2] == 3.toByte() || data[2] == 5.toByte() || data[2] == 7.toByte()) &&
                (data[3] == 4.toByte() || data[3] == 6.toByte() || data[3] == 8.toByte())

        @JvmStatic
        fun safeName(value: String?): String {
            var cleaned = value?.replace(Regex("[^A-Za-z0-9._-]"), "_")?.replace(Regex("_+"), "_") ?: "subtitle"
            cleaned = cleaned.replaceFirst(Regex("^\\.+"), "")
            return if (cleaned.isBlank()) "subtitle" else cleaned.substring(0, minOf(100, cleaned.length))
        }
    }

    private val maxDownloadBytes: Long
    private val maxExtractedBytes: Long
    private val maxArchiveEntries: Int

    constructor() : this(DEFAULT_MAX_DOWNLOAD_BYTES, DEFAULT_MAX_EXTRACTED_BYTES, DEFAULT_MAX_ARCHIVE_ENTRIES)

    constructor(maxDownloadBytes: Long, maxExtractedBytes: Long, maxArchiveEntries: Int) {
        require(maxDownloadBytes > 0 && maxExtractedBytes > 0 && maxArchiveEntries > 0) {
            "Subtitle download limits must be positive"
        }
        this.maxDownloadBytes = maxDownloadBytes
        this.maxExtractedBytes = maxExtractedBytes
        this.maxArchiveEntries = maxArchiveEntries
    }

    @Throws(IOException::class)
    fun store(candidate: SubtitleCandidate, input: InputStream, destination: Path): List<Path> {
        val downloaded = readBounded(input, maxDownloadBytes, "Subtitle download is too large")
        Files.createDirectories(destination)
        val root = destination.toAbsolutePath().normalize()
        if (!Files.isDirectory(root) || Files.isSymbolicLink(root)) {
            throw IOException("Subtitle destination must be a real directory")
        }
        val zip = candidate.archive || isZip(downloaded)
        val files = mutableListOf<Path>()
        try {
            if (zip) extractZip(downloaded, root, files)
            else storeDirect(candidate, downloaded, root, files)
            checkInterrupted()
            if (files.isEmpty()) throw IOException("No supported subtitle files found")
            return files.toList()
        } catch (error: IOException) {
            for (file in files) {
                try { Files.deleteIfExists(file) } catch (cleanup: IOException) { error.addSuppressed(cleanup) }
            }
            throw error
        } catch (error: RuntimeException) {
            for (file in files) {
                try { Files.deleteIfExists(file) } catch (cleanup: IOException) { error.addSuppressed(cleanup) }
            }
            throw error
        }
    }

    @Throws(IOException::class)
    private fun storeDirect(candidate: SubtitleCandidate, data: ByteArray, root: Path, files: MutableList<Path>) {
        var ext = supportedExtension(candidate.format)
        if (ext.isEmpty()) ext = extension(candidate.title)
        if (ext.isEmpty()) throw IOException("Unsupported subtitle format")
        val stem = safeName(candidate.id)
        val target = uniqueTarget(root, "$stem.$ext")
        files.add(target)
        Files.write(target, data, StandardOpenOption.WRITE)
    }

    @Throws(IOException::class)
    private fun extractZip(data: ByteArray, root: Path, files: MutableList<Path>) {
        var total = 0L
        var entries = 0
        ZipInputStream(ByteArrayInputStream(data)).use { zip ->
            var entry: ZipEntry?
            while (zip.nextEntry.also { entry = it } != null) {
                if (++entries > maxArchiveEntries) throw IOException("Subtitle archive has too many entries")
                val entryName = entry!!.name.replace('\\', '/')
                val entryPath = try {
                    Path.of(entryName).normalize()
                } catch (error: RuntimeException) {
                    throw IOException("Unsafe subtitle archive path", error)
                }
                if (entryPath.isAbsolute || entryPath.startsWith("..")) {
                    throw IOException("Unsafe subtitle archive path")
                }
                val ext = extension(entryName)
                if (entry.isDirectory || ext.isEmpty()) {
                    total += copyBounded(zip, null, maxExtractedBytes - total,
                        "Subtitle archive expands beyond its limit")
                    zip.closeEntry()
                    continue
                }
                var filename = safeName(entryPath.fileName.toString())
                if (!filename.lowercase().endsWith(".$ext")) {
                    filename += ".$ext"
                }
                val extracted = readBounded(zip, maxExtractedBytes - total,
                    "Subtitle archive expands beyond its limit")
                total += extracted.size
                val target = uniqueTarget(root, filename)
                files.add(target)
                Files.write(target, extracted, StandardOpenOption.WRITE)
                zip.closeEntry()
            }
        }
    }

    @Throws(IOException::class)
    private fun readBounded(input: InputStream, limit: Long, message: String): ByteArray {
        val output = ByteArrayOutputStream()
        copyBounded(input, output, limit, message)
        return output.toByteArray()
    }

    @Throws(IOException::class)
    private fun copyBounded(input: InputStream, output: ByteArrayOutputStream?, limit: Long, message: String): Long {
        if (limit < 0L) throw IOException(message)
        val buffer = ByteArray(8192)
        var total = 0L
        checkInterrupted()
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
            checkInterrupted()
            total += read
            if (total > limit) throw IOException(message)
            output?.write(buffer, 0, read)
        }
        checkInterrupted()
        return total
    }

    @Throws(InterruptedIOException::class)
    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw InterruptedIOException("Subtitle import cancelled")
    }

    @Throws(IOException::class)
    private fun uniqueTarget(root: Path, filename: String): Path {
        val ext = extension(filename)
        val suffix = if (ext.isEmpty()) "" else ".$ext"
        val stem = if (suffix.isEmpty()) filename else filename.substring(0, filename.length - suffix.length)
        for (index in 0 until 1000) {
            val candidate = if (index == 0) "$stem$suffix" else "$stem-$index$suffix"
            val target = checkedTarget(root, candidate)
            try {
                return Files.createFile(target)
            } catch (ignored: FileAlreadyExistsException) {
            }
        }
        throw IOException("Unable to allocate subtitle filename")
    }

    @Throws(IOException::class)
    private fun checkedTarget(root: Path, filename: String): Path {
        val target = root.resolve(filename).normalize()
        if (!target.parent.equals(root) || Files.isSymbolicLink(target)) {
            throw IOException("Unsafe subtitle archive path")
        }
        return target
    }
}
