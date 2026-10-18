package com.wuming.tv.core.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFileClassifierTest {

    @Test
    fun recognizesCommonPlaybackFilesCaseInsensitively() {
        assertTrue(MediaFileClassifier.isVideoExtension(" MKV "))
        assertTrue(MediaFileClassifier.isVideoExtension(".m3u8"))
        assertTrue(MediaFileClassifier.isVideoFileName("folder/Movie.WEBM"))
    }

    @Test
    fun rejectsProjectAndMetadataFilesFromLegacyList() {
        assertFalse(MediaFileClassifier.isVideoExtension("xml"))
        assertFalse(MediaFileClassifier.isVideoExtension("prproj"))
        assertFalse(MediaFileClassifier.isVideoFileName("README"))
    }

    @Test
    fun cloudListAcceptsEveryProviderContainer() {
        val containers = arrayOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "ts", "m2ts")
        for (container in containers) {
            assertTrue(container, MediaFileClassifier.isCloudVideoFileName("film.$container"))
            assertTrue(container, MediaFileClassifier.isCloudVideoFileName("film.${container.uppercase()}"))
        }
    }

    @Test
    fun cloudListStaysNarrowerThanThePlaybackList() {
        assertFalse(MediaFileClassifier.isCloudVideoFileName("segment.m3u8"))
        assertFalse(MediaFileClassifier.isCloudVideoFileName("raw.h264"))
        assertFalse(MediaFileClassifier.isCloudVideoFileName("disc.vob"))
        assertFalse(MediaFileClassifier.isCloudVideoFileName("backup.zip"))
    }

    @Test
    fun cloudListUsesTheLastExtensionAndNeverTrims() {
        assertTrue(MediaFileClassifier.isCloudVideoFileName("clip.ts.mp4"))
        assertFalse(MediaFileClassifier.isCloudVideoFileName("clip.mp4.backup"))
        assertFalse(MediaFileClassifier.isCloudVideoFileName("film.mp4 "))
        assertFalse(MediaFileClassifier.isCloudVideoFileName("no-extension"))
        assertFalse(MediaFileClassifier.isCloudVideoFileName("trailing."))
        assertFalse(MediaFileClassifier.isCloudVideoFileName(""))
        assertFalse(MediaFileClassifier.isCloudVideoFileName(null))
    }

    @Test
    fun cloudExtensionsAreKnownVideoExtensions() {
        for (extension in MediaFileClassifier.cloudVideoExtensions()) {
            assertTrue(extension, MediaFileClassifier.isVideoExtension(extension))
        }
    }
}
