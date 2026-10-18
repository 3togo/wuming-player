package com.wuming.tv.core.media

import com.wuming.tv.core.media.catalog.MediaManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

class OpenDrivePlaybackSessionTest {

    @Test
    fun failedSourceAdvancesToNextOrderedSource() {
        val resolver = resolver(AtomicInteger())
        val manifest = manifest(source("https://first.example/video.mp4", 100),
            source("https://second.example/video.mp4", 10))
        val session = OpenDrivePlaybackSession(manifest,
            MediaResolverRegistry(listOf(resolver)))

        assertEquals("https://first.example/video.mp4", session.target().url)
        assertEquals("https://second.example/video.mp4", session.failover().url)
    }

    @Test
    fun expiringTargetIsRefreshedBeforeConsumerReceivesIt() {
        val calls = AtomicInteger()
        val session = OpenDrivePlaybackSession(
            manifest(source("https://refresh.example/video.mp4", 1)),
            MediaResolverRegistry(listOf(resolver(calls))))

        session.target()
        session.target()

        assertEquals(2, calls.get())
    }

    @Test
    fun explicitRefreshReResolvesSameSourceWithoutFailingItOver() {
        val calls = AtomicInteger()
        val resolver = object : SourceResolver {
            override fun adapterId() = "test.session"
            override fun resolve(source: MediaSourceDescriptor): PlaybackTarget {
                val generation = calls.incrementAndGet()
                return PlaybackTarget("https://refresh.example/video-$generation.mp4", emptyMap(), 0L)
            }
        }
        val session = OpenDrivePlaybackSession(
            manifest(source("https://refresh.example/video.mp4", 1)),
            MediaResolverRegistry(listOf(resolver)))

        assertEquals("https://refresh.example/video-1.mp4", session.target().url)
        assertEquals("https://refresh.example/video-2.mp4", session.refresh().url)
        assertEquals(2, calls.get())
    }

    @Test
    fun failoverEmitsWarningLogWithManifestUriAndFailedSource() {
        val records = mutableListOf<LogRecord>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { records.add(record) }
            override fun flush() {}
            override fun close() {}
        }
        val logger = Logger.getLogger("OpenDrivePlaybackSession")
        logger.addHandler(handler)
        logger.level = Level.ALL
        try {
            val resolver = object : SourceResolver {
                override fun adapterId() = "test.session"
                override fun resolve(source: MediaSourceDescriptor): PlaybackTarget =
                    PlaybackTarget(source.locator["url"]!!, emptyMap(), 0L)
            }
            val manifest = manifest(source("https://first.example/video.mp4", 100),
                source("https://second.example/video.mp4", 10))
            val session = OpenDrivePlaybackSession(manifest,
                MediaResolverRegistry(listOf(resolver)))

            session.target()
            session.failover()

            assertTrue(records.any { it.level == Level.WARNING &&
                it.message.contains("failover") })
        } finally {
            logger.removeHandler(handler)
        }
    }

    @Test
    fun refreshEmitsInfoLogWithManifestUri() {
        val records = mutableListOf<LogRecord>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { records.add(record) }
            override fun flush() {}
            override fun close() {}
        }
        val logger = Logger.getLogger("OpenDrivePlaybackSession")
        logger.addHandler(handler)
        logger.level = Level.ALL
        try {
            val resolver = object : SourceResolver {
                override fun adapterId() = "test.session"
                override fun resolve(source: MediaSourceDescriptor): PlaybackTarget =
                    PlaybackTarget(source.locator["url"]!!, emptyMap(), 0L)
            }
            val session = OpenDrivePlaybackSession(
                manifest(source("https://refresh.example/video.mp4", 1)),
                MediaResolverRegistry(listOf(resolver)))

            session.refresh()

            assertTrue(records.any { it.level == Level.INFO &&
                it.message.contains("refresh") })
        } finally {
            logger.removeHandler(handler)
        }
    }

    private fun resolver(calls: AtomicInteger): SourceResolver = object : SourceResolver {
        override fun adapterId() = "test.session"
        override fun resolve(source: MediaSourceDescriptor): PlaybackTarget {
            calls.incrementAndGet()
            return PlaybackTarget(source.locator["url"]!!, emptyMap(),
                System.currentTimeMillis() + 1_000)
        }
    }

    private fun manifest(vararg sources: MediaSourceDescriptor): MediaManifest =
        MediaManifest(OpenDriveUri("local", OpenDriveUri.Kind.ASSET, "session"),
            "Session", 1, listOf(*sources))

    private fun source(url: String, priority: Int): MediaSourceDescriptor =
        MediaSourceDescriptor("test.session", 1, mapOf("url" to url),
            MediaFingerprint.sha256(url), priority, true)
}
