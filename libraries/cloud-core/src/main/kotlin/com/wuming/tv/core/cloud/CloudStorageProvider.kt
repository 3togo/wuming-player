package com.wuming.tv.core.cloud

/** Platform-neutral storage port implemented by a host's reviewed cloud providers. */
interface CloudStorageProvider : CloudAccountProvider {
    @Throws(Exception::class)
    fun list(parentId: String): List<OpenDriveItem>

    fun supportsShare(url: String): Boolean = false

    @Throws(Exception::class)
    fun listShare(url: String): List<OpenDriveItem> {
        throw UnsupportedOperationException("Shared links are not supported")
    }

    @Throws(Exception::class)
    fun resolve(itemId: String): OpenDrivePlayback

    fun persistHandle(itemId: String): Boolean = false
}
