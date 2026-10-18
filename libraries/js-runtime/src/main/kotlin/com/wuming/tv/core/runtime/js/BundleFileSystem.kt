package com.wuming.tv.core.runtime.js

import org.graalvm.polyglot.io.FileSystem
import java.net.URI
import java.nio.channels.SeekableByteChannel
import java.nio.file.AccessMode
import java.nio.file.DirectoryStream
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileAttribute

/** Read-only filesystem view restricted to one prepared module bundle. */
internal class BundleFileSystem(root: Path) : FileSystem {
    private val delegate = FileSystem.newDefaultFileSystem()
    private val root = root.toRealPath()

    override fun parsePath(uri: URI): Path = delegate.parsePath(uri)
    override fun parsePath(path: String): Path = delegate.parsePath(path)

    override fun checkAccess(path: Path, modes: Set<AccessMode>, vararg linkOptions: LinkOption) {
        requireInside(path)
        require(modes.none { it == AccessMode.WRITE }) { "JavaScript module filesystem is read-only" }
        delegate.checkAccess(path, modes, *linkOptions)
    }

    override fun newByteChannel(
        path: Path,
        options: Set<OpenOption>,
        vararg attrs: FileAttribute<*>,
    ): SeekableByteChannel {
        requireInside(path)
        require(options.none { it in WRITE_OPTIONS }) { "JavaScript module filesystem is read-only" }
        return delegate.newByteChannel(path, options, *attrs)
    }

    override fun newDirectoryStream(
        dir: Path,
        filter: DirectoryStream.Filter<in Path>,
    ): DirectoryStream<Path> {
        requireInside(dir)
        return delegate.newDirectoryStream(dir, filter)
    }

    override fun toAbsolutePath(path: Path): Path = delegate.toAbsolutePath(path).also(::requireInside)
    override fun toRealPath(path: Path, vararg linkOptions: LinkOption): Path =
        delegate.toRealPath(path, *linkOptions).also(::requireInside)
    override fun readAttributes(path: Path, attributes: String, vararg options: LinkOption): Map<String, Any> {
        requireInside(path)
        return delegate.readAttributes(path, attributes, *options)
    }

    override fun createDirectory(dir: Path, vararg attrs: FileAttribute<*>) = denied()
    override fun delete(path: Path) = denied()
    override fun getTempDirectory(): Path = root

    private fun requireInside(path: Path) {
        val absolute = if (path.isAbsolute) path.normalize() else root.resolve(path).normalize()
        require(absolute.startsWith(root)) { "JavaScript module attempted filesystem access outside its bundle" }
    }

    private fun denied(): Nothing = throw UnsupportedOperationException("JavaScript module filesystem is read-only")

    private companion object {
        val WRITE_OPTIONS = setOf(
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND,
            StandardOpenOption.CREATE,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.DELETE_ON_CLOSE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
    }
}
