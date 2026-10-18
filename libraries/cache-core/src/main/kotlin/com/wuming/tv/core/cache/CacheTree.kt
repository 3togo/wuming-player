package com.wuming.tv.core.cache

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** A bounded file walk. Links are counted/deleted as links and are never followed. */
object CacheTree {
    private const val MAX_ENTRIES = 100_000

    fun size(directory: File): Long {
        val root = directory.toPath()
        if (!Files.exists(root, NOFOLLOW_LINKS)) return 0
        var entries = 0
        var bytes = 0L
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            private fun count() {
                if (++entries > MAX_ENTRIES) throw IOException("Cache contains too many entries")
            }

            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                count()
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                count()
                if (attrs.isRegularFile) bytes = Math.addExact(bytes, attrs.size())
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, error: IOException): FileVisitResult {
                if (error is NoSuchFileException) return FileVisitResult.CONTINUE
                throw error
            }
        })
        return bytes
    }

    fun delete(target: File) {
        val root = target.toPath()
        if (!Files.exists(root, NOFOLLOW_LINKS)) return
        var entries = 0
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            private fun count() {
                if (++entries > MAX_ENTRIES) throw IOException("Cache contains too many entries")
            }

            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                count()
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                count()
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, error: IOException): FileVisitResult {
                if (error is NoSuchFileException) return FileVisitResult.CONTINUE
                throw error
            }

            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                if (error != null && error !is NoSuchFileException) throw error
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    fun children(directory: File): List<File> {
        val root = directory.toPath()
        if (!Files.exists(root, NOFOLLOW_LINKS)) return emptyList()
        if (Files.isSymbolicLink(root)) throw IOException("Cache directory is a symbolic link")
        Files.newDirectoryStream(root).use { stream ->
            val children = ArrayList<File>()
            for (child in stream) {
                if (children.size >= MAX_ENTRIES) throw IOException("Cache contains too many entries")
                children.add(child.toFile())
            }
            return children
        }
    }
}
