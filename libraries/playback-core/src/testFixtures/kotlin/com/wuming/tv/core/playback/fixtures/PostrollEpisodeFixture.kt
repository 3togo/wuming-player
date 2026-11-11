package com.wuming.tv.core.playback.fixtures

import com.google.gson.JsonParser
import com.wuming.tv.core.playback.HlsResolutionAdFilter
import java.net.URI
import java.util.Base64

/** Captured playlist and ffprobe/SPS metadata from 醉梦 ep02, 2026-11-04.
 * TS packets below are generated around real codec parameters; no video frames
 * or media fingerprints are stored. See the resource README for provenance. */
class PostrollEpisodeFixture {
    private fun resource(name: String) = checkNotNull(javaClass.getResourceAsStream("/hls/zuimeng-02/$name"))
        .bufferedReader().use { it.readText() }

    val playlist = resource("episode.m3u8")
    val urls = playlist.lineSequence().filter { it.isNotBlank() && !it.startsWith('#') }.toList()
    val records = JsonParser.parseString(resource("probes.json")).asJsonArray.associate { entry ->
        val value = entry.asJsonObject
        value.get("index").asInt to value
    }
    val creditsOnly = playlist.substringBeforeLast("#EXT-X-DISCONTINUITY") + "#EXT-X-ENDLIST\n"

    fun index(url: String) = urls.indexOfFirst { URI(it).path.substringAfterLast('/') == URI(url).path.substringAfterLast('/') }

    fun info(index: Int): HlsResolutionAdFilter.VideoInfo {
        val record = records.getValue(index)
        val rate = record.get("frameRate").asString.split('/').map(String::toDouble)
        return HlsResolutionAdFilter.VideoInfo(record.get("width").asInt, record.get("height").asInt,
            record.get("pts").asDouble, rate[0] / rate[1])
    }

    fun prefix(url: String): ByteArray {
        val index = index(url)
        val record = records[index] ?: records.getValue(697)
        // The desktop's initial compatibility check requests segment 0. It is
        // synthetic programme transport, not a measured timestamp regression.
        val pts = records[index]?.get("pts")?.asDouble ?: 1.48
        return transport(record.get("codecParameters").asString, pts)
    }

    private fun transport(codec: String, seconds: Double): ByteArray {
        val ticks = Math.round(seconds * 90000)
        val pes = byteArrayOf(0, 0, 1, 0xe0.toByte(), 0, 0, 0x80.toByte(), 0x80.toByte(), 5,
            (0x21 or ((ticks shr 29).toInt() and 14)).toByte(), (ticks shr 22).toByte(),
            (((ticks shr 14).toInt() and 254) or 1).toByte(), (ticks shr 7).toByte(), ((ticks shl 1) or 1).toByte()) +
            Base64.getDecoder().decode(codec)
        val pmt = byteArrayOf(0, 2, 0xb0.toByte(), 18, 0, 1, 0xc1.toByte(), 0, 0,
            0xe1.toByte(), 0, 0xf0.toByte(), 0, 0x1b, 0xe1.toByte(), 0, 0xf0.toByte(), 0, 0, 0, 0, 0)
        return packet(4096, pmt) + packet(256, pes) + packet(8191, byteArrayOf()) + packet(8191, byteArrayOf())
    }

    private fun packet(pid: Int, payload: ByteArray) = ByteArray(188) { 0xff.toByte() }.apply {
        require(payload.size <= 184)
        this[0] = 0x47
        this[1] = ((pid shr 8) or 0x40).toByte()
        this[2] = pid.toByte()
        this[3] = 0x10
        payload.copyInto(this, 4)
    }
}
