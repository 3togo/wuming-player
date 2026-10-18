package com.wuming.tv.desktop.cast

import org.junit.Assert.*
import org.junit.Test
import org.fourthline.cling.binding.annotations.AnnotationLocalServiceBinder
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes
import org.fourthline.cling.support.avtransport.AVTransportException
import org.fourthline.cling.support.model.TransportState
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files

class DesktopCastingTest {
    private val id = UnsignedIntegerFourBytes(0)
    private class Player : CastPlayer {
        var state = "STOPPED"
        var position = 0.0
        var url = ""
        var closed = false
        var broken = false
        override fun load(uri: String) { if (broken) error("Failed"); url = uri; state = "PAUSED_PLAYBACK" }
        override fun play() { state = "PLAYING" }
        override fun pause() { state = "PAUSED_PLAYBACK" }
        override fun stop() { state = "STOPPED" }
        override fun seek(seconds: Double) { position = seconds }
        override fun volume(volume: Int) {}
        override fun mute(mute: Boolean) {}
        override fun snapshot() = CastPlayer.Snapshot(state, position, 90.0, 50, false)
        override fun close() { closed = true }
    }
    @Test fun receiverBindsStandardServices() {
        val binder = AnnotationLocalServiceBinder()
        val av = binder.read(DesktopAvTransport::class.java)
        listOf("SetAVTransportURI", "Play", "Pause", "Stop", "Seek", "GetPositionInfo", "GetTransportInfo").forEach {
            assertNotNull(av.getAction(it))
        }
        assertNotNull(binder.read(DesktopRenderingControl::class.java).getAction("SetVolume"))
    }
    @Test fun receiverHonorsTransportOrderAndClosesItsPlayer() {
        val player = Player()
        val av = DesktopAvTransport(player)
        assertEquals(TransportState.NO_MEDIA_PRESENT, av.getTransportInfo(id).currentTransportState)
        av.setAVTransportURI(id, "http://192.168.1.10/video.mp4", "")
        assertEquals("", player.url)
        assertEquals(TransportState.STOPPED, av.getTransportInfo(id).currentTransportState)
        av.play(id, "1")
        assertEquals("http://192.168.1.10/video.mp4", player.url)
        av.pause(id)
        assertEquals(TransportState.PAUSED_PLAYBACK, av.getTransportInfo(id).currentTransportState)
        av.seek(id, "REL_TIME", "00:01:02.500")
        assertEquals(62.5, player.position, 0.001)
        av.stop(id)
        av.play(id, "1")
        assertEquals(TransportState.PLAYING, av.getTransportInfo(id).currentTransportState)
        player.state = "STOPPED" // natural completion is reflected, not inferred from query failures
        assertEquals(TransportState.STOPPED, av.getTransportInfo(id).currentTransportState)
        av.disable()
        assertTrue(player.closed)
        assertThrows(AVTransportException::class.java) { av.play(id, "1") }
    }
    @Test fun receiverRejectsUnsafeUrlsBadInstancesAndUnsupportedActions() {
        val av = DesktopAvTransport(Player())
        listOf("file:///etc/passwd", "javascript:foo", "ftp://example.com/movie", "https:///missing", "http://user:pass@host/video").forEach {
            assertThrows(AVTransportException::class.java) { av.setAVTransportURI(id, it, "") }
        }
        assertThrows(AVTransportException::class.java) { av.play(id, "1") }
        assertThrows(AVTransportException::class.java) { av.getMediaInfo(UnsignedIntegerFourBytes(2)) }
        av.setAVTransportURI(id, "http://host/video", "")
        assertThrows(AVTransportException::class.java) { av.play(id, "2") }
        assertThrows(AVTransportException::class.java) { av.seek(id, "REL_TIME", "00:99:00") }
        assertThrows(AVTransportException::class.java) { av.next(id) }
    }
    @Test fun playerFailureIsNotReportedAsPlaying() {
        val player = Player()
        val av = DesktopAvTransport(player)
        av.setAVTransportURI(id, "https://host/video", "")
        player.broken = true
        assertThrows(AVTransportException::class.java) { av.play(id, "1") }
        assertEquals(TransportState.STOPPED, av.getTransportInfo(id).currentTransportState)
    }
    @Test fun fileRangesPreserveOpenEndedSuffixAndEmptyFileSemantics() {
        assertArrayEquals(longArrayOf(3, 9), CastFileServer.range("bytes=3-", 10))
        assertArrayEquals(longArrayOf(0, 9), CastFileServer.range("bytes=-20", 10))
        assertArrayEquals(longArrayOf(2, 9), CastFileServer.range("bytes=2-100", 10))
        assertArrayEquals(longArrayOf(0, -1), CastFileServer.range(null, 0))
        listOf("bytes=-", "bytes=-0", "bytes=8-2", "bytes=10-", "bytes=0-1,3-4", "bytes=9223372036854775808-").forEach {
            assertThrows(IllegalArgumentException::class.java) { CastFileServer.range(it, 10) }
        }
        assertThrows(IllegalArgumentException::class.java) { CastFileServer.range("bytes=0-", 0) }
    }
    @Test fun selectedFileSupportsRangeHeadAndRevocation() {
        val file = Files.createTempFile("cast-test", ".mp4")
        Files.writeString(file, "0123456789")
        val server = CastFileServer(file, "127.0.0.1")
        val client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).build()
        fun request(path: String = server.url(), method: String = "GET", range: String? = null): HttpResponse<String> {
            val builder = HttpRequest.newBuilder(URI(path)).timeout(java.time.Duration.ofSeconds(2)).method(method, HttpRequest.BodyPublishers.noBody())
            if (range != null) builder.header("Range", range)
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        }
        try {
            assertEquals("0123456789", request().body())
            val partial = request(range = "bytes=3-6")
            assertEquals(206, partial.statusCode())
            assertEquals("3456", partial.body())
            assertEquals("bytes 3-6/10", partial.headers().firstValue("Content-Range").get())
            assertEquals("89", request(range = "bytes=-2").body())
            assertEquals(416, request(range = "bytes=50-").statusCode())
            assertEquals(416, request(range = "bytes=0-1,4-5").statusCode())
            assertEquals("", request(method = "HEAD").body())
            assertEquals(404, request(path = server.url() + "/../private").statusCode())
            assertEquals(405, request(method = "POST").statusCode())
            server.close()
            assertThrows(java.io.IOException::class.java) { request() }
        } finally { server.close(); Files.deleteIfExists(file) }
    }
}
