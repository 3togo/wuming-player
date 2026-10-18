package com.wuming.tv.core.cloud

/** Provider-neutral file metadata. Provider IDs and credentials never belong here. */
class OpenDriveItem(
    val id: String,
    name: String,
    val directory: Boolean,
    size: Long
) {
    val name: String = if (name.isBlank()) if (directory) "Folder" else "Cloud file" else name
    val size: Long = maxOf(0L, size)

    init {
        if (id.isBlank()) throw IllegalArgumentException("Item ID is required")
    }

    @JvmName("id")
    fun idAccessor(): String = id
    @JvmName("name")
    fun nameAccessor(): String = name
    @JvmName("directory")
    fun directoryAccessor(): Boolean = directory
    @JvmName("size")
    fun sizeAccessor(): Long = size
}
